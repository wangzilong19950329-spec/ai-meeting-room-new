package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchDeliveryConfigMapper;
import com.aimeeting.room.dao.StockWatchDeliveryLogMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.entity.StockWatchDeliveryConfig;
import com.aimeeting.room.entity.StockWatchDeliveryLog;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockWatchDeliveryService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Set<String> VALID_FREQUENCIES = new LinkedHashSet<>(
            Arrays.asList("IMMEDIATE", "INTERVAL", "DAILY_CLOSE", "MANUAL"));

    private final StockWatchDeliveryConfigMapper configMapper;
    private final StockWatchDeliveryLogMapper logMapper;
    private final StockWatchTechnicalAnalysisMapper analysisMapper;
    private final NotificationService notificationService;
    private final AtomicBoolean dispatching = new AtomicBoolean(false);

    @Value("${stock-watch.delivery.enabled:true}")
    private boolean enabled;

    @Value("${stock-watch.delivery.batch-limit:100}")
    private int batchLimit;

    @Scheduled(
            fixedDelayString = "${stock-watch.delivery.scan-interval-ms:300000}",
            initialDelayString = "${stock-watch.delivery.initial-delay-ms:45000}"
    )
    public void scheduledDispatch() {
        if (!enabled) {
            return;
        }
        if (!dispatching.compareAndSet(false, true)) {
            return;
        }
        try {
            ZonedDateTime now = ZonedDateTime.now(CHINA_ZONE);
            for (StockWatchDeliveryConfig config : configMapper.selectEnabled()) {
                if (isDue(config, now)) {
                    dispatchConfigUnlocked(config, "scheduled");
                }
            }
        } catch (Exception e) {
            log.warn("stock-watch delivery scheduled dispatch failed: {}", e.getMessage());
        } finally {
            dispatching.set(false);
        }
    }

    public Map<String, Object> getConfig(String symbol, String market) {
        StockRef ref = parseStockRef(symbol, market);
        StockWatchDeliveryConfig config = configMapper.selectBySymbolMarket(ref.symbol, ref.market);
        return toConfigResponse(config == null ? defaultConfig(ref) : config);
    }

    public Map<String, Object> saveConfig(Map<String, Object> body) {
        StockRef ref = parseStockRef(text(body, "symbol", ""), text(body, "market", ""));
        StockWatchDeliveryConfig existing = configMapper.selectBySymbolMarket(ref.symbol, ref.market);
        StockWatchDeliveryConfig config = existing == null ? defaultConfig(ref) : existing;

        List<String> recipients = parseRecipients(body.get("recipients"));
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("收件邮箱为必填项");
        }

        String frequencyType = normalizeFrequencyType(body.get("frequencyType"));
        int intervalMinutes = normalizeIntervalMinutes(body.get("intervalMinutes"), frequencyType);

        config.setSymbol(ref.symbol);
        config.setMarket(ref.market);
        config.setStockName(text(body, "stockName", text(body, "name", config.getStockName())));
        config.setEnabled(bool(body.get("enabled"), false));
        config.setRecipientsJson(JSON.toJSONString(recipients));
        config.setSenderMode("SYSTEM");
        config.setFrequencyType(frequencyType);
        config.setIntervalMinutes(intervalMinutes);
        config.setTriggerPolicyJson(JSON.toJSONString(normalizeTriggerPolicy(body.get("triggerPolicy"))));
        if (config.getActiveFrom() == null || bool(body.get("resetActiveFrom"), false)) {
            config.setActiveFrom(new Date());
        }
        config.setLastDispatchStatus("CONFIG_SAVED");
        config.setLastDispatchMessage("单股邮件推送配置已保存");

        if (existing == null) {
            configMapper.insert(config);
        } else {
            configMapper.update(config);
        }
        return toConfigResponse(configMapper.selectBySymbolMarket(ref.symbol, ref.market));
    }

    public Map<String, Object> preview(String symbol, String market) {
        StockRef ref = parseStockRef(symbol, market);
        StockWatchDeliveryConfig config = configMapper.selectBySymbolMarket(ref.symbol, ref.market);
        if (config == null) {
            config = defaultConfig(ref);
        }
        DispatchPlan plan = buildPlan(config, false);
        Map<String, Object> result = toConfigResponse(config);
        result.put("preview", plan.previewRecords);
        result.put("pendingEmailCount", plan.pendingEmailCount());
        result.put("pendingRecordCount", plan.pendingRecordCount());
        result.put("skippedSentCount", plan.skippedSentCount);
        result.put("skippedPolicyCount", plan.skippedPolicyCount);
        return result;
    }

    public Map<String, Object> dispatchNow(String symbol, String market) {
        StockRef ref = parseStockRef(symbol, market);
        StockWatchDeliveryConfig config = configMapper.selectBySymbolMarket(ref.symbol, ref.market);
        if (config == null) {
            throw new IllegalArgumentException("请先保存该股票的邮件配置");
        }
        if (!dispatching.compareAndSet(false, true)) {
            Map<String, Object> busy = new LinkedHashMap<>();
            busy.put("status", "BUSY");
            busy.put("message", "已有发送任务正在执行，请稍后再试");
            return busy;
        }
        try {
            return dispatchConfigUnlocked(config, "manual");
        } finally {
            dispatching.set(false);
        }
    }

    public Map<String, Object> testEmail(String symbol, String market, Map<String, Object> body) {
        StockRef ref = parseStockRef(symbol, market);
        StockWatchDeliveryConfig config = configMapper.selectBySymbolMarket(ref.symbol, ref.market);
        List<String> recipients = parseRecipients(body == null ? null : body.get("recipients"));
        if (recipients.isEmpty() && config != null) {
            recipients = parseRecipients(config.getRecipientsJson());
        }
        if (recipients.isEmpty()) {
            throw new IllegalArgumentException("收件邮箱为必填项");
        }

        String subject = "Stock Watch · 邮件测试 · " + ref.symbol;
        String content = "这是一封股票盯盘单股配置测试邮件。\n\n"
                + "标的：" + ref.symbol + "." + ref.market + "\n"
                + "发送时间：" + LocalDateTime.now(CHINA_ZONE).format(DATE_TIME_FORMATTER) + "\n"
                + "说明：测试邮件不会写入分析结论发送日志。";
        NotificationService.EmailSendResult sendResult = notificationService
                .sendStockWatchAlertToRecipients(subject, content, recipients, null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", sendResult.isSuccess());
        result.put("messageId", sendResult.getMessageId());
        result.put("errorMessage", sendResult.getErrorMessage());
        result.put("recipients", recipients.stream().map(this::maskEmail).collect(Collectors.toList()));
        if (!sendResult.isSuccess()) {
            throw new IllegalStateException("测试邮件发送失败：" + sendResult.getErrorMessage());
        }
        return result;
    }

    private Map<String, Object> dispatchConfigUnlocked(StockWatchDeliveryConfig config, String reason) {
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            configMapper.updateDispatchStatus(config.getId(), "DISABLED", "邮件推送未启用", null);
            return dispatchResult(config, "DISABLED", "邮件推送未启用", 0, 0, 0);
        }
        List<String> recipients = parseRecipients(config.getRecipientsJson());
        if (recipients.isEmpty()) {
            configMapper.updateDispatchStatus(config.getId(), "INVALID_CONFIG", "收件邮箱为空", null);
            return dispatchResult(config, "INVALID_CONFIG", "收件邮箱为空", 0, 0, 0);
        }

        DispatchPlan plan = buildPlan(config, true);
        if (plan.itemsByRecipient.isEmpty()) {
            String message = "没有符合条件且未发送过的分析结论";
            configMapper.updateDispatchStatus(config.getId(), "NO_NEW_ANALYSIS", message, null);
            return dispatchResult(config, "NO_NEW_ANALYSIS", message, 0, plan.skippedSentCount, plan.skippedPolicyCount);
        }

        int sentRecordCount = 0;
        int failedRecordCount = 0;
        String lastError = "";
        for (Map.Entry<String, List<PendingDelivery>> entry : plan.itemsByRecipient.entrySet()) {
            String recipient = entry.getKey();
            List<PendingDelivery> pending = entry.getValue();
            if (pending.isEmpty()) {
                continue;
            }
            NotificationService.EmailSendResult sendResult = notificationService.sendStockWatchAlertToRecipients(
                    buildSubject(config, pending),
                    buildEmailContent(config, pending, reason),
                    Collections.singletonList(recipient),
                    null
            );
            for (PendingDelivery delivery : pending) {
                if (sendResult.isSuccess()) {
                    logMapper.updateStatus(delivery.log.getId(), "SENT", sendResult.getMessageId(), null);
                    sentRecordCount += 1;
                } else {
                    lastError = sendResult.getErrorMessage();
                    logMapper.updateStatus(delivery.log.getId(), "FAILED", null, lastError);
                    failedRecordCount += 1;
                }
            }
        }

        if (sentRecordCount > 0) {
            String message = "已发送 " + sentRecordCount + " 条未发送分析结论";
            if (failedRecordCount > 0) {
                message += "，失败 " + failedRecordCount + " 条";
            }
            configMapper.updateDispatchStatus(config.getId(), failedRecordCount > 0 ? "PARTIAL_SENT" : "SENT", message, new Date());
            return dispatchResult(config, failedRecordCount > 0 ? "PARTIAL_SENT" : "SENT", message,
                    sentRecordCount, plan.skippedSentCount, plan.skippedPolicyCount);
        }

        String message = "邮件发送失败：" + firstNonBlank(lastError, "unknown");
        configMapper.updateDispatchStatus(config.getId(), "FAILED", message, null);
        return dispatchResult(config, "FAILED", message, 0, plan.skippedSentCount, plan.skippedPolicyCount);
    }

    private DispatchPlan buildPlan(StockWatchDeliveryConfig config, boolean claimLogs) {
        DispatchPlan plan = new DispatchPlan();
        List<String> recipients = parseRecipients(config.getRecipientsJson());
        if (recipients.isEmpty() || config.getActiveFrom() == null) {
            return plan;
        }
        List<StockWatchTechnicalAnalysis> candidates = analysisMapper.selectSuccessfulSince(
                config.getSymbol(), config.getMarket(), config.getActiveFrom(), Math.max(1, batchLimit));

        for (StockWatchTechnicalAnalysis analysis : candidates) {
            if (!matchesPolicy(analysis, config.getTriggerPolicyJson())) {
                plan.skippedPolicyCount += 1;
                continue;
            }

            int pendingRecipients = 0;
            for (String recipient : recipients) {
                String recipientHash = hashEmail(recipient);
                StockWatchDeliveryLog existing = logMapper.selectByAnalysisRecipient(analysis.getId(), recipientHash);
                if (existing != null && "SENT".equalsIgnoreCase(existing.getStatus())) {
                    plan.skippedSentCount += 1;
                    continue;
                }

                StockWatchDeliveryLog deliveryLog = claimLogs
                        ? ensurePendingLog(config, analysis, recipient, recipientHash, existing)
                        : existing;
                if (claimLogs && deliveryLog == null) {
                    continue;
                }
                pendingRecipients += 1;
                if (claimLogs) {
                    plan.itemsByRecipient
                            .computeIfAbsent(recipient, ignored -> new ArrayList<>())
                            .add(new PendingDelivery(analysis, deliveryLog));
                }
            }

            if (pendingRecipients > 0) {
                plan.previewRecords.add(toAnalysisPreview(analysis, pendingRecipients));
            }
        }
        return plan;
    }

    private StockWatchDeliveryLog ensurePendingLog(StockWatchDeliveryConfig config,
                                                   StockWatchTechnicalAnalysis analysis,
                                                   String recipient,
                                                   String recipientHash,
                                                   StockWatchDeliveryLog existing) {
        if (existing != null) {
            if ("SENT".equalsIgnoreCase(existing.getStatus())) {
                return null;
            }
            logMapper.resetPending(existing.getId());
            existing.setStatus("PENDING");
            return existing;
        }

        StockWatchDeliveryLog logRecord = new StockWatchDeliveryLog();
        logRecord.setConfigId(config.getId());
        logRecord.setAnalysisId(analysis.getId());
        logRecord.setSymbol(config.getSymbol());
        logRecord.setMarket(config.getMarket());
        logRecord.setRecipientHash(recipientHash);
        logRecord.setRecipientMasked(maskEmail(recipient));
        logRecord.setStatus("PENDING");
        try {
            logMapper.insert(logRecord);
            return logRecord;
        } catch (DuplicateKeyException e) {
            StockWatchDeliveryLog duplicate = logMapper.selectByAnalysisRecipient(analysis.getId(), recipientHash);
            if (duplicate == null || "SENT".equalsIgnoreCase(duplicate.getStatus())) {
                return null;
            }
            logMapper.resetPending(duplicate.getId());
            duplicate.setStatus("PENDING");
            return duplicate;
        }
    }

    private boolean isDue(StockWatchDeliveryConfig config, ZonedDateTime now) {
        String frequency = firstNonBlank(config.getFrequencyType(), "MANUAL").toUpperCase(Locale.ROOT);
        if ("MANUAL".equals(frequency)) {
            return false;
        }
        if ("IMMEDIATE".equals(frequency)) {
            return true;
        }
        if ("INTERVAL".equals(frequency)) {
            Date last = config.getLastDispatchAt();
            if (last == null) {
                return true;
            }
            ZonedDateTime lastAt = ZonedDateTime.ofInstant(last.toInstant(), CHINA_ZONE);
            int interval = Math.max(5, config.getIntervalMinutes() == null ? 120 : config.getIntervalMinutes());
            return Duration.between(lastAt, now).toMinutes() >= interval;
        }
        if ("DAILY_CLOSE".equals(frequency)) {
            LocalTime closeDispatchTime = LocalTime.of(15, 10);
            if (now.toLocalTime().isBefore(closeDispatchTime)) {
                return false;
            }
            Date last = config.getLastDispatchAt();
            return last == null || ZonedDateTime.ofInstant(last.toInstant(), CHINA_ZONE).toLocalDate().isBefore(now.toLocalDate());
        }
        return false;
    }

    private boolean matchesPolicy(StockWatchTechnicalAnalysis analysis, String triggerPolicyJson) {
        JSONObject policy = parsePolicy(triggerPolicyJson);
        if (!bool(policy.get("sendOnNewAnalysis"), true)) {
            return false;
        }
        double minConfidence = number(policy.get("minConfidence"), 0.0);
        if (minConfidence > 0 && analysis.getConfidence() != null && analysis.getConfidence() < minConfidence) {
            return false;
        }

        List<String> tones = toStringList(policy.get("tones"));
        if (!tones.isEmpty()) {
            String tone = firstNonBlank(analysis.getTone(), "").toLowerCase(Locale.ROOT);
            String stance = firstNonBlank(analysis.getStance(), "").toLowerCase(Locale.ROOT);
            boolean matched = tones.stream()
                    .map(item -> item.toLowerCase(Locale.ROOT))
                    .anyMatch(item -> item.equals(tone) || stance.contains(item));
            if (!matched) {
                return false;
            }
        }

        List<String> keywords = toStringList(policy.get("keywords"));
        if (!keywords.isEmpty()) {
            String text = String.join("\n",
                    firstNonBlank(analysis.getTitle(), ""),
                    firstNonBlank(analysis.getConclusion(), ""),
                    firstNonBlank(analysis.getStance(), ""),
                    firstNonBlank(analysis.getEvidenceJson(), ""));
            return keywords.stream().anyMatch(text::contains);
        }
        return true;
    }

    private Map<String, Object> toConfigResponse(StockWatchDeliveryConfig config) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", config.getId());
        result.put("symbol", config.getSymbol());
        result.put("market", config.getMarket());
        result.put("stockName", config.getStockName());
        result.put("enabled", Boolean.TRUE.equals(config.getEnabled()));
        result.put("recipients", parseRecipients(config.getRecipientsJson()));
        result.put("maskedRecipients", parseRecipients(config.getRecipientsJson()).stream().map(this::maskEmail).collect(Collectors.toList()));
        result.put("senderMode", firstNonBlank(config.getSenderMode(), "SYSTEM"));
        result.put("frequencyType", firstNonBlank(config.getFrequencyType(), "MANUAL"));
        result.put("intervalMinutes", config.getIntervalMinutes() == null ? 120 : config.getIntervalMinutes());
        result.put("triggerPolicy", parsePolicy(config.getTriggerPolicyJson()));
        result.put("activeFrom", formatDateTime(config.getActiveFrom()));
        result.put("lastDispatchStatus", config.getLastDispatchStatus());
        result.put("lastDispatchMessage", config.getLastDispatchMessage());
        result.put("lastDispatchAt", formatDateTime(config.getLastDispatchAt()));
        result.put("stats", deliveryStats(config));
        return result;
    }

    private Map<String, Object> dispatchResult(StockWatchDeliveryConfig config,
                                               String status,
                                               String message,
                                               int sentCount,
                                               int skippedSentCount,
                                               int skippedPolicyCount) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", config.getSymbol());
        result.put("market", config.getMarket());
        result.put("status", status);
        result.put("message", message);
        result.put("sentCount", sentCount);
        result.put("skippedSentCount", skippedSentCount);
        result.put("skippedPolicyCount", skippedPolicyCount);
        result.put("ranAt", LocalDateTime.now(CHINA_ZONE).format(DATE_TIME_FORMATTER));
        return result;
    }

    private Map<String, Object> deliveryStats(StockWatchDeliveryConfig config) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("PENDING", 0);
        stats.put("SENT", 0);
        stats.put("FAILED", 0);
        if (config == null || config.getId() == null) {
            return stats;
        }
        for (Map<String, Object> row : logMapper.countByConfig(config.getId())) {
            String status = String.valueOf(row.getOrDefault("status", row.getOrDefault("STATUS", "")));
            Object countValue = row.containsKey("count") ? row.get("count") : row.get("COUNT");
            stats.put(status, integer(countValue, 0));
        }
        return stats;
    }

    private Map<String, Object> toAnalysisPreview(StockWatchTechnicalAnalysis analysis, int pendingRecipients) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", analysis.getId());
        item.put("date", analysis.getAnalysisDate() == null ? "" : DATE_FORMATTER.format(
                Instant.ofEpochMilli(analysis.getAnalysisDate().getTime()).atZone(CHINA_ZONE).toLocalDate()));
        item.put("time", firstNonBlank(analysis.getRunSlot(), "--"));
        item.put("title", firstNonBlank(analysis.getTitle(), "技术面分析结论"));
        item.put("stance", analysis.getStance());
        item.put("tone", analysis.getTone());
        item.put("confidence", analysis.getConfidence());
        item.put("conclusion", firstNonBlank(analysis.getConclusion(), analysis.getRawResponse()));
        item.put("pendingRecipients", pendingRecipients);
        return item;
    }

    private String buildSubject(StockWatchDeliveryConfig config, List<PendingDelivery> pending) {
        String name = firstNonBlank(config.getStockName(), config.getSymbol());
        return "Stock Watch 技术面结论 · " + name + " · 新增 " + pending.size() + " 条";
    }

    private String buildEmailContent(StockWatchDeliveryConfig config, List<PendingDelivery> pending, String reason) {
        StringBuilder builder = new StringBuilder();
        appendTitle(builder, "Stock Watch 技术面结论");
        appendSection(builder, "一、邮件摘要");
        appendLine(builder, "标的", firstNonBlank(config.getStockName(), config.getSymbol())
                + "（" + config.getSymbol() + "." + config.getMarket() + "）");
        appendLine(builder, "新增结论", pending.size() + " 条");
        appendLine(builder, "发送时间", LocalDateTime.now(CHINA_ZONE).format(DATE_TIME_FORMATTER));
        appendLine(builder, "发送方式", "scheduled".equalsIgnoreCase(reason) ? "定时发送" : "手动发送");
        appendParagraph(builder, "本邮件只包含此前未成功发送给当前收件人的分析结论。", "  ");
        for (int i = 0; i < pending.size(); i++) {
            StockWatchTechnicalAnalysis analysis = pending.get(i).analysis;
            appendAnalysisBlock(builder, i + 1, analysis);
        }
        return builder.toString();
    }

    private void appendAnalysisBlock(StringBuilder builder, int index, StockWatchTechnicalAnalysis analysis) {
        appendSection(builder, "二、结论 " + index + "：" + firstNonBlank(analysis.getTitle(), "技术面分析结论"));
        appendLine(builder, "分析时间", firstNonBlank(formatAnalysisDate(analysis), "--"));
        appendLine(builder, "立场", firstNonBlank(analysis.getStance(), "--"));
        appendLine(builder, "情绪", firstNonBlank(analysis.getTone(), "--"));
        appendLine(builder, "置信度", analysis.getConfidence() == null ? "--" : String.format(Locale.ROOT, "%.2f", analysis.getConfidence()));

        appendSection(builder, "三、DeepSeek 分析建议");
        appendParagraph(builder, firstNonBlank(cleanAnalysisText(analysis.getConclusion()),
                cleanAnalysisText(analysis.getRawResponse()), "本轮无可展示结论。"), "  ");

        List<String> evidence = parseEvidence(analysis.getEvidenceJson());
        if (!evidence.isEmpty()) {
            appendSection(builder, "四、关键依据");
            for (String item : evidence) {
                appendParagraph(builder, item, "  - ");
            }
        }

        if (StringUtils.hasText(analysis.getConversationUrl())) {
            appendSection(builder, "五、溯源");
            appendLine(builder, "DeepSeek 原会话", analysis.getConversationUrl());
        }
    }

    private void appendTitle(StringBuilder builder, String title) {
        builder.append(title).append("\n")
                .append(repeat("=", Math.max(8, title.length()))).append("\n");
    }

    private void appendSection(StringBuilder builder, String title) {
        builder.append("\n").append(title).append("\n");
    }

    private void appendLine(StringBuilder builder, String label, Object value) {
        builder.append("  ").append(label).append("：")
                .append(value == null ? "--" : String.valueOf(value))
                .append("\n");
    }

    private void appendParagraph(StringBuilder builder, String text, String prefix) {
        String value = firstNonBlank(text, "--")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        String safePrefix = prefix == null ? "" : prefix;
        for (String line : value.split("\\n+")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            builder.append(safePrefix).append(trimmed).append("\n");
        }
    }

    private String formatAnalysisDate(StockWatchTechnicalAnalysis analysis) {
        String date = "";
        if (analysis.getAnalysisDate() != null) {
            date = DATE_FORMATTER.format(Instant.ofEpochMilli(analysis.getAnalysisDate().getTime())
                    .atZone(CHINA_ZONE)
                    .toLocalDate());
        }
        String slot = firstNonBlank(analysis.getRunSlot(), "");
        return firstNonBlank((date + " " + slot).trim(), formatDateTime(analysis.getGmtCreate()));
    }

    private String cleanAnalysisText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String value = text.trim();
        int jsonStart = value.indexOf("{\"stance\"");
        int jsonEnd = jsonStart >= 0 ? value.indexOf("\"evidence\"", jsonStart) : -1;
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return value.substring(0, jsonStart).trim();
        }
        return value;
    }

    private List<String> parseEvidence(String evidenceJson) {
        if (!StringUtils.hasText(evidenceJson)) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = JSON.parseArray(evidenceJson);
            List<String> values = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                String value = array.getString(i);
                if (StringUtils.hasText(value)) {
                    values.add(value.trim());
                }
            }
            return values;
        } catch (Exception ignored) {
            return Collections.singletonList(evidenceJson.trim());
        }
    }

    private String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private StockWatchDeliveryConfig defaultConfig(StockRef ref) {
        StockWatchDeliveryConfig config = new StockWatchDeliveryConfig();
        config.setSymbol(ref.symbol);
        config.setMarket(ref.market);
        config.setEnabled(false);
        config.setRecipientsJson("[]");
        config.setSenderMode("SYSTEM");
        config.setFrequencyType("MANUAL");
        config.setIntervalMinutes(120);
        config.setTriggerPolicyJson(JSON.toJSONString(defaultTriggerPolicy()));
        config.setActiveFrom(new Date());
        config.setLastDispatchStatus("NOT_CONFIGURED");
        config.setLastDispatchMessage("尚未保存单股邮件配置");
        return config;
    }

    private JSONObject normalizeTriggerPolicy(Object raw) {
        JSONObject fallback = defaultTriggerPolicy();
        JSONObject input = parsePolicy(raw);
        fallback.put("sendOnNewAnalysis", bool(input.get("sendOnNewAnalysis"), true));
        fallback.put("tones", toJsonArray(toStringList(input.get("tones"))));
        fallback.put("keywords", toJsonArray(toStringList(input.get("keywords"))));
        fallback.put("minConfidence", Math.max(0.0, Math.min(1.0, number(input.get("minConfidence"), 0.0))));
        return fallback;
    }

    private JSONObject defaultTriggerPolicy() {
        JSONObject policy = new JSONObject(true);
        policy.put("sendOnNewAnalysis", true);
        policy.put("tones", new JSONArray());
        policy.put("keywords", new JSONArray());
        policy.put("minConfidence", 0);
        return policy;
    }

    private JSONObject parsePolicy(Object raw) {
        if (raw instanceof JSONObject) {
            return normalizeJsonObject((JSONObject) raw);
        }
        if (raw instanceof Map) {
            JSONObject object = new JSONObject(true);
            ((Map<?, ?>) raw).forEach((key, value) -> {
                if (key != null) {
                    object.put(String.valueOf(key), value);
                }
            });
            return object;
        }
        if (raw instanceof String && StringUtils.hasText((String) raw)) {
            try {
                return JSON.parseObject((String) raw);
            } catch (Exception ignored) {
                return defaultTriggerPolicy();
            }
        }
        return defaultTriggerPolicy();
    }

    private JSONObject normalizeJsonObject(JSONObject input) {
        return input == null ? defaultTriggerPolicy() : input;
    }

    private List<String> parseRecipients(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        List<String> values = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            for (int i = 0; i < array.size(); i++) {
                values.add(array.getString(i));
            }
        } else if (raw instanceof Collection) {
            for (Object item : (Collection<?>) raw) {
                values.add(item == null ? "" : String.valueOf(item));
            }
        } else {
            String text = String.valueOf(raw);
            if (text.trim().startsWith("[") && text.trim().endsWith("]")) {
                try {
                    return parseRecipients(JSON.parseArray(text));
                } catch (Exception ignored) {
                    // fallback to delimiter parsing
                }
            }
            values.addAll(Arrays.asList(text.split("[,;\\n\\s]+")));
        }
        return values.stream()
                .map(item -> item == null ? "" : item.trim())
                .filter(item -> !item.isEmpty())
                .filter(item -> EMAIL_PATTERN.matcher(item).matches())
                .distinct()
                .collect(Collectors.toList());
    }

    private List<String> toStringList(Object raw) {
        if (raw == null) {
            return Collections.emptyList();
        }
        if (raw instanceof JSONArray) {
            JSONArray array = (JSONArray) raw;
            List<String> result = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                String value = array.getString(i);
                if (StringUtils.hasText(value)) {
                    result.add(value.trim());
                }
            }
            return result;
        }
        if (raw instanceof Collection) {
            return ((Collection<?>) raw).stream()
                    .map(item -> item == null ? "" : String.valueOf(item).trim())
                    .filter(StringUtils::hasText)
                    .distinct()
                    .collect(Collectors.toList());
        }
        String value = String.valueOf(raw).trim();
        if (!StringUtils.hasText(value)) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split("[,;\\n]+"))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList());
    }

    private JSONArray toJsonArray(List<String> values) {
        JSONArray array = new JSONArray();
        if (values != null) {
            array.addAll(values);
        }
        return array;
    }

    private String normalizeFrequencyType(Object raw) {
        String value = raw == null ? "MANUAL" : String.valueOf(raw).trim().toUpperCase(Locale.ROOT);
        if ("EVERY_30_MINUTES".equals(value)) {
            return "INTERVAL";
        }
        return VALID_FREQUENCIES.contains(value) ? value : "MANUAL";
    }

    private int normalizeIntervalMinutes(Object raw, String frequencyType) {
        int fallback = "INTERVAL".equals(frequencyType) ? 120 : 120;
        int value = integer(raw, fallback);
        return Math.max(5, Math.min(24 * 60, value));
    }

    private StockRef parseStockRef(String symbol, String market) {
        if (!StringUtils.hasText(symbol)) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        String cleanSymbol = symbol.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String cleanMarket = market == null ? "" : market.trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher prefixed = java.util.regex.Pattern
                .compile("^(sh|sz|bj)[._-]?(\\d{6})$")
                .matcher(cleanSymbol);
        if (prefixed.matches()) {
            return new StockRef(prefixed.group(2), prefixed.group(1));
        }
        java.util.regex.Matcher suffixed = java.util.regex.Pattern
                .compile("^(\\d{6})(?:[._-]?(sh|sz|bj))?$")
                .matcher(cleanSymbol);
        if (!suffixed.matches()) {
            throw new IllegalArgumentException("股票代码格式不正确");
        }
        String normalizedSymbol = suffixed.group(1);
        String normalizedMarket = StringUtils.hasText(suffixed.group(2))
                ? suffixed.group(2)
                : (StringUtils.hasText(cleanMarket) ? cleanMarket : inferMarket(normalizedSymbol));
        return new StockRef(normalizedSymbol, normalizedMarket);
    }

    private String inferMarket(String symbol) {
        if (symbol.startsWith("6") || symbol.startsWith("9")) return "sh";
        if (symbol.startsWith("4") || symbol.startsWith("8")) return "bj";
        return "sz";
    }

    private String hashEmail(String email) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(email.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            return String.valueOf(email.toLowerCase(Locale.ROOT).hashCode());
        }
    }

    private String maskEmail(String email) {
        if (!StringUtils.hasText(email) || !email.contains("@")) {
            return "***";
        }
        String[] parts = email.split("@", 2);
        String name = parts[0];
        String maskedName = name.length() <= 2 ? name.charAt(0) + "*" : name.substring(0, 2) + "***" + name.substring(name.length() - 1);
        return maskedName + "@" + parts[1];
    }

    private String formatDateTime(Date date) {
        if (date == null) {
            return "";
        }
        return LocalDateTime.ofInstant(date.toInstant(), CHINA_ZONE).format(DATE_TIME_FORMATTER);
    }

    private String text(Map<String, Object> map, String key, String fallback) {
        if (map == null) return fallback;
        Object value = map.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private boolean bool(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean) return (Boolean) raw;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (Arrays.asList("true", "1", "yes", "y", "on").contains(value)) return true;
        if (Arrays.asList("false", "0", "no", "n", "off").contains(value)) return false;
        return fallback;
    }

    private int integer(Object raw, int fallback) {
        if (raw instanceof Number) return ((Number) raw).intValue();
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double number(Object raw, double fallback) {
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static class DispatchPlan {
        private final Map<String, List<PendingDelivery>> itemsByRecipient = new LinkedHashMap<>();
        private final List<Map<String, Object>> previewRecords = new ArrayList<>();
        private int skippedSentCount = 0;
        private int skippedPolicyCount = 0;

        private int pendingEmailCount() {
            return itemsByRecipient.values().stream().mapToInt(List::size).sum();
        }

        private int pendingRecordCount() {
            return previewRecords.size();
        }
    }

    private static class PendingDelivery {
        private final StockWatchTechnicalAnalysis analysis;
        private final StockWatchDeliveryLog log;

        private PendingDelivery(StockWatchTechnicalAnalysis analysis, StockWatchDeliveryLog log) {
            this.analysis = analysis;
            this.log = log;
        }
    }

    private static class StockRef {
        private final String symbol;
        private final String market;

        private StockRef(String symbol, String market) {
            this.symbol = symbol;
            this.market = market;
        }
    }
}
