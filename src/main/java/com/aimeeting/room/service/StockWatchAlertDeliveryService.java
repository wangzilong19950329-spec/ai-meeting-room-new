package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.aimeeting.room.dao.StockWatchAlertDeliveryObligationMapper;
import com.aimeeting.room.dao.StockWatchDeliveryConfigMapper;
import com.aimeeting.room.entity.StockWatchAlertDeliveryObligation;
import com.aimeeting.room.entity.StockWatchDeliveryConfig;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockWatchAlertDeliveryService {

    private static final int MAX_ATTEMPTS = 3;
    private static final long LEASE_MILLIS = 60_000L;

    private final StockWatchAlertDeliveryObligationMapper obligationMapper;
    private final StockWatchDeliveryConfigMapper deliveryConfigMapper;
    private final NotificationService notificationService;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public List<StockWatchAlertDeliveryObligation> createObligations(AlertRequest request) {
        List<StockWatchAlertDeliveryObligation> rows =
                transactionTemplate.execute(status -> createObligationsInternal(request));
        return rows == null ? List.of() : rows;
    }

    @Value("${stock-watch.monitor.ai-alert-cooldown-minutes:30}")
    private int defaultAiCooldownMinutes;

    private List<StockWatchAlertDeliveryObligation> createObligationsInternal(AlertRequest request) {
        Long analysisDemandId = request.getInlineAnalysisId() == null ? request.getAnalysisDemandId() : null;
        List<StockWatchAlertDeliveryObligation> result = new ArrayList<>();
        for (String recipient : request.getRecipients()) {
            String normalized = recipient == null ? "" : recipient.trim().toLowerCase();
            if (!StringUtils.hasText(normalized)) continue;
            String recipientHash = sha256(normalized);
            String eventKey = sha256("stock-alert-event-v1|" + request.getRuleId() + "|" + request.getSymbol()
                    + "|" + request.getMarket() + "|" + text(request.getTargetFactObservationKey()) + "|"
                    + request.getSignalFingerprint());
            StockWatchAlertDeliveryObligation existing =
                    obligationMapper.selectByEventRecipient(eventKey, recipientHash);
            if (existing != null) {
                result.add(existing);
                continue;
            }
            StockWatchAlertDeliveryObligation row = new StockWatchAlertDeliveryObligation();
            row.setEventKey(eventKey);
            row.setOpenEpisodeKey(sha256("stock-alert-episode-v1|" + request.getRuleId() + "|"
                    + request.getSymbol() + "|" + request.getMarket() + "|" + request.getSignalFingerprint()
                    + "|" + recipientHash));
            row.setConfigId(request.getConfigId());
            row.setRuleId(request.getRuleId());
            row.setSymbol(request.getSymbol());
            row.setMarket(request.getMarket());
            row.setStockName(request.getStockName());
            row.setRecipientHash(recipientHash);
            row.setRecipientAddress(normalized);
            row.setSenderSnapshotJson(systemSenderSnapshot());
            row.setTargetFactObservationId(request.getTargetFactObservationId());
            row.setTargetFactObservationKey(request.getTargetFactObservationKey());
            row.setTargetSourceTimeEpochMs(request.getTargetSourceTimeEpochMs());
            row.setTradeDate(request.getTradeDate());
            row.setSignalFingerprint(request.getSignalFingerprint());
            row.setInlineAnalysisId(request.getInlineAnalysisId());
            row.setAnalysisDemandId(analysisDemandId);
            row.setDeadlineAt(request.getDeadlineAt());
            row.setInitialSubject(request.getInitialSubject());
            row.setInitialBody(request.getInitialBody());
            row.setInitialMessageId("sw-alert-initial-" + eventKey.substring(0, 24)
                    + "-" + recipientHash.substring(0, 12));
            row.setInitialStatus("PENDING");
            row.setSupplementMessageId("sw-alert-supplement-" + eventKey.substring(0, 24)
                    + "-" + recipientHash.substring(0, 12));
            row.setSupplementStatus(request.getInlineAnalysisId() != null
                    ? "SKIPPED_INLINE"
                    : (analysisDemandId == null ? "SKIPPED_BY_POLICY" : "WAITING_ANALYSIS"));
            row.setRowVersion(0L);
            try {
                obligationMapper.insert(row);
            } catch (DuplicateKeyException duplicate) {
                obligationMapper.releaseExpiredEpisode(row.getOpenEpisodeKey(), new Date());
                try {
                    obligationMapper.insert(row);
                } catch (DuplicateKeyException raced) {
                    row = obligationMapper.selectByEventRecipient(eventKey, recipientHash);
                }
            }
            if (row != null) {
                if (analysisDemandId != null && row.getAnalysisDemandId() == null) {
                    obligationMapper.attachDemand(row.getId(), analysisDemandId);
                    row = obligationMapper.selectById(row.getId());
                }
                result.add(row);
            }
        }
        return result;
    }

    public DeliveryResult createAndSend(AlertRequest request) {
        List<StockWatchAlertDeliveryObligation> obligations = createObligations(request);
        if (obligations.isEmpty()) return DeliveryResult.failed("未创建任何邮件交付义务");
        int sent = 0;
        String firstError = null;
        String firstMessageId = null;
        for (StockWatchAlertDeliveryObligation row : obligations) {
            if ("SENT".equals(row.getInitialStatus())) {
                sent++;
                if (firstMessageId == null) firstMessageId = row.getInitialMessageId();
                continue;
            }
            DispatchResult dispatch = dispatchInitial(row);
            if (dispatch.sent) {
                sent++;
                if (firstMessageId == null) firstMessageId = dispatch.messageId;
            } else if (firstError == null) {
                firstError = dispatch.error;
            }
        }
        if (sent == obligations.size()) return DeliveryResult.sent(firstMessageId, sent);
        if (sent > 0) return DeliveryResult.partial(firstMessageId, sent, obligations.size(), firstError);
        return DeliveryResult.failed(firstError == null ? "SMTP 发送失败" : firstError);
    }

    public boolean hasDurableCooldown(String ruleId,
                                      String symbol,
                                      String market,
                                      String signalFingerprint,
                                      ZonedDateTime now) {
        return obligationMapper.hasDurableCooldown(ruleId, symbol, market, signalFingerprint,
                Date.from(now.toInstant())) > 0;
    }

    @Scheduled(
            fixedDelayString = "${stock-watch.alert-delivery.scan-interval-ms:60000}",
            initialDelayString = "${stock-watch.alert-delivery.initial-delay-ms:45000}"
    )
    public void reconcile() {
        if (!running.compareAndSet(false, true)) return;
        try {
            Date now = new Date();
            List<StockWatchAlertDeliveryObligation> initial =
                    obligationMapper.selectInitialDispatchable(now, 20);
            if (initial != null) {
                for (StockWatchAlertDeliveryObligation row : initial) dispatchInitial(row);
            }
            List<StockWatchAlertDeliveryObligation> supplement =
                    obligationMapper.selectSupplementDispatchable(now, 20);
            if (supplement != null) {
                for (StockWatchAlertDeliveryObligation row : supplement) dispatchSupplement(row);
            }
        } catch (Exception e) {
            log.warn("stock-watch alert delivery reconcile failed error={}", diagnostic(e));
        } finally {
            running.set(false);
        }
    }

    private DispatchResult dispatchInitial(StockWatchAlertDeliveryObligation candidate) {
        StockWatchAlertDeliveryObligation current = obligationMapper.selectById(candidate.getId());
        if (current == null) return DispatchResult.failed("obligation_missing");
        if ("SENT".equals(current.getInitialStatus())) {
            return DispatchResult.sent(current.getInitialMessageId());
        }
        String lease = UUID.randomUUID().toString();
        Date now = new Date();
        if (obligationMapper.claimInitial(current.getId(), value(current.getRowVersion()), lease,
                new Date(now.getTime() + LEASE_MILLIS), now) != 1) {
            return DispatchResult.failed("initial_claim_conflict");
        }
        NotificationService.EmailSendResult send = notificationService.sendStockWatchAlertToRecipients(
                current.getInitialSubject(), current.getInitialBody(),
                List.of(current.getRecipientAddress()), null, current.getInitialMessageId());
        if (send.isSuccess()) {
            int cooldownMinutes = effectiveCooldownMinutes(current.getConfigId());
            Date suppressedUntil = new Date(now.getTime() + Duration.ofMinutes(cooldownMinutes).toMillis());
            obligationMapper.markInitialSent(current.getId(), lease, now, suppressedUntil);
            updateConfig(current, "SENT", "Stock Watch 异动提醒已发送：" + current.getInitialSubject(), now);
            return DispatchResult.sent(send.getMessageId());
        }
        int attempts = value(current.getInitialAttemptCount()) + 1;
        String error = text(send.getErrorMessage());
        if (attempts >= MAX_ATTEMPTS) {
            obligationMapper.markInitialDeadLetter(current.getId(), lease, error);
            updateConfig(current, "FAILED", "Stock Watch 异动提醒发送失败：" + error, null);
        } else {
            obligationMapper.markInitialRetry(current.getId(), lease,
                    new Date(now.getTime() + retryDelayMillis(attempts)), error);
        }
        return DispatchResult.failed(error);
    }

    private DispatchResult dispatchSupplement(StockWatchAlertDeliveryObligation candidate) {
        StockWatchAlertDeliveryObligation current = obligationMapper.selectById(candidate.getId());
        if (current == null) return DispatchResult.failed("obligation_missing");
        if ("SENT".equals(current.getSupplementStatus())) {
            return DispatchResult.sent(current.getSupplementMessageId());
        }
        String lease = UUID.randomUUID().toString();
        Date now = new Date();
        if (obligationMapper.claimSupplement(current.getId(), value(current.getRowVersion()), lease,
                new Date(now.getTime() + LEASE_MILLIS), now) != 1) {
            return DispatchResult.failed("supplement_claim_conflict");
        }
        NotificationService.EmailSendResult send = notificationService.sendStockWatchAlertToRecipients(
                current.getSupplementSubject(), current.getSupplementBody(),
                List.of(current.getRecipientAddress()), null, current.getSupplementMessageId());
        if (send.isSuccess()) {
            obligationMapper.markSupplementSent(current.getId(), lease, now);
            return DispatchResult.sent(send.getMessageId());
        }
        int attempts = value(current.getSupplementAttemptCount()) + 1;
        String error = text(send.getErrorMessage());
        if (attempts >= MAX_ATTEMPTS) {
            obligationMapper.markSupplementDeadLetter(current.getId(), lease, error);
        } else {
            obligationMapper.markSupplementRetry(current.getId(), lease,
                    new Date(now.getTime() + retryDelayMillis(attempts)), error);
        }
        return DispatchResult.failed(error);
    }

    private int effectiveCooldownMinutes(Long configId) {
        if (configId == null) return Math.max(1, defaultAiCooldownMinutes);
        List<StockWatchDeliveryConfig> enabled = deliveryConfigMapper.selectEnabled();
        StockWatchDeliveryConfig config = (enabled == null ? List.<StockWatchDeliveryConfig>of() : enabled).stream()
                .filter(item -> configId.equals(item.getId()))
                .findFirst().orElse(null);
        int baseCooldown = Math.max(1, defaultAiCooldownMinutes);
        if (config == null || !"INTERVAL".equalsIgnoreCase(config.getFrequencyType())
                || config.getIntervalMinutes() == null) {
            return baseCooldown;
        }
        return Math.max(baseCooldown, config.getIntervalMinutes());
    }

    private void updateConfig(StockWatchAlertDeliveryObligation row,
                              String status,
                              String message,
                              Date dispatchAt) {
        if (row.getConfigId() != null) {
            deliveryConfigMapper.updateDispatchStatus(row.getConfigId(), status, message, dispatchAt);
        }
    }

    private long retryDelayMillis(int attempts) {
        return Duration.ofMinutes(Math.min(30, 1L << Math.max(0, attempts - 1))).toMillis();
    }

    private String systemSenderSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("senderMode", "SYSTEM");
        snapshot.put("credentialPersisted", false);
        return JSON.toJSONString(snapshot);
    }

    private long value(Long raw) {
        return raw == null ? 0L : raw;
    }

    private int value(Integer raw) {
        return raw == null ? 0 : raw;
    }

    private String text(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private String diagnostic(Exception e) {
        return e == null ? "unknown_error" : (StringUtils.hasText(e.getMessage())
                ? e.getMessage() : e.getClass().getSimpleName());
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : hash) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }

    @Data
    public static class AlertRequest {
        private Long configId;
        private String ruleId;
        private String symbol;
        private String market;
        private String stockName;
        private List<String> recipients = new ArrayList<>();
        private Long targetFactObservationId;
        private String targetFactObservationKey;
        private Long targetSourceTimeEpochMs;
        private Date tradeDate;
        private String signalFingerprint;
        private Long inlineAnalysisId;
        private Long analysisDemandId;
        private Date deadlineAt;
        private String initialSubject;
        private String initialBody;
    }

    @Data
    public static class DeliveryResult {
        private boolean success;
        private boolean attempted;
        private String messageId;
        private String error;
        private int sentCount;
        private int totalCount;

        static DeliveryResult sent(String messageId, int count) {
            DeliveryResult result = new DeliveryResult();
            result.success = true;
            result.attempted = true;
            result.messageId = messageId;
            result.sentCount = count;
            result.totalCount = count;
            return result;
        }

        static DeliveryResult partial(String messageId, int sent, int total, String error) {
            DeliveryResult result = new DeliveryResult();
            result.attempted = true;
            result.messageId = messageId;
            result.sentCount = sent;
            result.totalCount = total;
            result.error = error;
            return result;
        }

        static DeliveryResult failed(String error) {
            DeliveryResult result = new DeliveryResult();
            result.attempted = true;
            result.error = error;
            return result;
        }
    }

    private static class DispatchResult {
        private final boolean sent;
        private final String messageId;
        private final String error;

        private DispatchResult(boolean sent, String messageId, String error) {
            this.sent = sent;
            this.messageId = messageId;
            this.error = error;
        }

        static DispatchResult sent(String messageId) {
            return new DispatchResult(true, messageId, null);
        }

        static DispatchResult failed(String error) {
            return new DispatchResult(false, null, error);
        }
    }
}
