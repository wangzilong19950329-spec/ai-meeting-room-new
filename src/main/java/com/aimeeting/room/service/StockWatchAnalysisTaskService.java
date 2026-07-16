package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.aimeeting.room.dao.StockWatchAnalysisTaskMapper;
import com.aimeeting.room.entity.StockWatchAnalysisTask;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StockWatchAnalysisTaskService {

    private final StockWatchAnalysisTaskMapper mapper;

    @Transactional
    public Reservation reserve(String submissionKey,
                               String requestDigest,
                               String purpose,
                               String symbol,
                               String market,
                               String periodType,
                               String observationKey,
                               int generation,
                               String provider,
                               Map<String, Object> requestEnvelope) {
        if (!StringUtils.hasText(submissionKey) || !StringUtils.hasText(requestDigest)) {
            throw new IllegalArgumentException("submission_key_and_request_digest_required");
        }
        StockWatchAnalysisTask existing = mapper.selectBySubmissionKey(submissionKey);
        if (existing != null) return Reservation.existing(existing);
        StockWatchAnalysisTask task = new StockWatchAnalysisTask();
        task.setSubmissionKey(submissionKey);
        task.setRequestDigest(requestDigest);
        task.setPurpose(purpose);
        task.setSymbol(symbol);
        task.setMarket(market);
        task.setPeriodType(periodType);
        task.setObservationKey(observationKey);
        task.setGeneration(generation);
        task.setStatus("RESERVED");
        task.setProvider(provider);
        task.setRequestEnvelopeJson(JSON.toJSONString(requestEnvelope == null ? new LinkedHashMap<>() : requestEnvelope));
        task.setRowVersion(0L);
        try {
            mapper.insert(task);
            return Reservation.created(task);
        } catch (DuplicateKeyException duplicate) {
            existing = mapper.selectBySubmissionKey(submissionKey);
            if (existing != null) return Reservation.existing(existing);
            throw duplicate;
        }
    }

    @Transactional
    public void markSubmitted(Reservation reservation, String provider) {
        if (reservation == null || !reservation.isNewSubmission()) return;
        StockWatchAnalysisTask task = reservation.getTask();
        int updated = mapper.markSubmitted(task.getId(), value(task.getRowVersion()), provider, new Date());
        if (updated != 1) throw new IllegalStateException("analysis_task_submission_state_conflict");
        task.setStatus("SUBMITTED");
        task.setProvider(provider);
        task.setRowVersion(value(task.getRowVersion()) + 1);
    }

    public void markSuccess(Reservation reservation,
                            String rawResponse,
                            String contentStatus,
                            String completionReason,
                            String providerStatus) {
        complete(reservation, "SUCCESS", rawResponse, contentStatus, completionReason, providerStatus, null);
    }

    public void markInvalid(Reservation reservation,
                            String rawResponse,
                            String contentStatus,
                            String completionReason,
                            String error) {
        complete(reservation, "INVALID_RESPONSE", rawResponse, contentStatus, completionReason, "INVALID", error);
    }

    public void markUnknown(Reservation reservation, String rawResponse, String error) {
        complete(reservation, "UNKNOWN", rawResponse, null, null, "UNKNOWN", error);
    }

    public void markFailed(Reservation reservation, String rawResponse, String error) {
        complete(reservation, "FAILED", rawResponse, null, null, "FAILED", error);
    }

    private void complete(Reservation reservation,
                          String status,
                          String rawResponse,
                          String contentStatus,
                          String completionReason,
                          String providerStatus,
                          String error) {
        if (reservation == null || !reservation.isNewSubmission()) return;
        String responseHash = StringUtils.hasText(rawResponse) ? sha256(rawResponse) : null;
        mapper.complete(reservation.getTask().getId(), status, responseHash, rawResponse, contentStatus,
                completionReason, providerStatus, new Date(), error);
        reservation.getTask().setStatus(status);
        reservation.getTask().setRawResponse(rawResponse);
        reservation.getTask().setContentStatus(contentStatus);
        reservation.getTask().setCompletionReason(completionReason);
        reservation.getTask().setProviderStatus(providerStatus);
        reservation.getTask().setErrorMessage(error);
    }

    public boolean shouldMarkUnknown(Throwable error) {
        String value = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        return value.contains("timeout") || value.contains("timed out") || value.contains("unexpected end")
                || value.contains("connection reset") || value.contains("bridge http 500") || value.contains("max_inflight");
    }

    public boolean shouldMarkInvalid(Throwable error) {
        String value = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        return error != null && "ModelResponseSchemaException".equals(error.getClass().getSimpleName())
                || value.contains("未完整生成") || value.contains("未返回分析正文")
                || value.contains("无法解析") || value.contains("invalid_response");
    }

    public String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : hash) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }

    private long value(Long raw) {
        return raw == null ? 0L : raw;
    }

    public static class Reservation {
        private final StockWatchAnalysisTask task;
        private final boolean newSubmission;

        private Reservation(StockWatchAnalysisTask task, boolean newSubmission) {
            this.task = task;
            this.newSubmission = newSubmission;
        }

        static Reservation created(StockWatchAnalysisTask task) {
            return new Reservation(task, true);
        }

        static Reservation existing(StockWatchAnalysisTask task) {
            return new Reservation(task, false);
        }

        public StockWatchAnalysisTask getTask() {
            return task;
        }

        public boolean isNewSubmission() {
            return newSubmission;
        }

        public boolean hasReusableSuccess() {
            return !newSubmission && "SUCCESS".equals(task.getStatus()) && StringUtils.hasText(task.getRawResponse());
        }

        public String refusalReason() {
            return "submission_already_exists:" + task.getStatus();
        }
    }
}
