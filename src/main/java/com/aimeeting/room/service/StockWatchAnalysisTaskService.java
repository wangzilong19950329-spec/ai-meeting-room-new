package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.aimeeting.room.dao.StockWatchAnalysisTaskMapper;
import com.aimeeting.room.dao.StockWatchAnalysisObservationIdentityMapper;
import com.aimeeting.room.entity.StockWatchAnalysisObservationIdentity;
import com.aimeeting.room.entity.StockWatchAnalysisTask;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockWatchAnalysisTaskService {

    public static final String TECHNICAL_RUNTIME_SCOPE = "TECHNICAL_RUNTIME";

    private final StockWatchAnalysisTaskMapper mapper;
    private final StockWatchAnalysisObservationIdentityMapper identityMapper;
    private final StockWatchV6RuntimeGate runtimeGate;

    public Reservation findCanonical(String symbol, String market, String periodType, String observationKey) {
        String identityKey = observationIdentityKey(TECHNICAL_RUNTIME_SCOPE,
                symbol, market, periodType, observationKey);
        StockWatchAnalysisObservationIdentity identity = identityMapper.selectByIdentityKey(identityKey);
        if (identity == null || identity.getCanonicalTaskId() == null) return null;
        StockWatchAnalysisTask task = mapper.selectById(identity.getCanonicalTaskId());
        if (task == null) {
            throw new IllegalStateException("analysis_identity_canonical_task_missing:" + identityKey);
        }
        return Reservation.existing(task, task.getRequestDigest());
    }

    @Transactional
    public Reservation claimCanonical(String submissionKey,
                                      String purpose,
                                      String symbol,
                                      String market,
                                      String periodType,
                                      String observationKey,
                                      String provider) {
        runtimeGate.assertReady();
        String normalizedSymbol = normalize(symbol, false, "symbol");
        String normalizedMarket = normalize(market, true, "market");
        String normalizedPeriod = normalize(periodType, true, "periodType");
        String normalizedObservation = requiredText(observationKey, "observationKey");
        String identityKey = observationIdentityKey(TECHNICAL_RUNTIME_SCOPE, normalizedSymbol, normalizedMarket,
                normalizedPeriod, normalizedObservation);
        StockWatchAnalysisObservationIdentity candidate = new StockWatchAnalysisObservationIdentity();
        candidate.setIdentityKey(identityKey);
        candidate.setAnalysisScope(TECHNICAL_RUNTIME_SCOPE);
        candidate.setSymbol(normalizedSymbol);
        candidate.setMarket(normalizedMarket);
        candidate.setPeriodType(normalizedPeriod);
        candidate.setObservationKey(normalizedObservation);
        candidate.setMigrationState("RUNTIME_CLAIMED");
        candidate.setRowVersion(0L);
        identityMapper.insertIfAbsent(candidate);
        StockWatchAnalysisObservationIdentity identity = identityMapper.selectByIdentityKeyForUpdate(identityKey);
        if (identity == null) {
            throw new IllegalStateException("analysis_identity_insert_or_lock_failed:" + identityKey);
        }
        if (identity.getCanonicalTaskId() != null) {
            StockWatchAnalysisTask existing = mapper.selectById(identity.getCanonicalTaskId());
            if (existing == null) throw new IllegalStateException("analysis_identity_canonical_task_missing:" + identityKey);
            return Reservation.existing(existing, existing.getRequestDigest());
        }
        List<StockWatchAnalysisTask> legacy = mapper.selectByObservation(
                normalizedSymbol, normalizedMarket, normalizedPeriod, normalizedObservation);
        if (legacy != null && legacy.size() > 1) {
            throw new IllegalStateException("analysis_identity_ambiguous_unmigrated_tasks:" + identityKey + ":" + legacy.size());
        }
        Reservation reservation;
        if (legacy != null && legacy.size() == 1) {
            reservation = Reservation.existing(legacy.get(0), legacy.get(0).getRequestDigest());
        } else {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("schema", "stock-analysis-claim-v1");
            envelope.put("submissionKey", submissionKey);
            reservation = reserve(submissionKey, sha256(submissionKey), purpose, normalizedSymbol,
                    normalizedMarket, normalizedPeriod, normalizedObservation, 0, provider, envelope);
        }
        int bound = identityMapper.bindCanonical(identity.getId(), value(identity.getRowVersion()),
                reservation.getTask().getId(), "CANONICAL");
        if (bound != 1) throw new IllegalStateException("analysis_identity_bind_conflict:" + identityKey);
        return reservation;
    }

    public Reservation find(String submissionKey) {
        if (!StringUtils.hasText(submissionKey)) return null;
        StockWatchAnalysisTask task = mapper.selectBySubmissionKey(submissionKey);
        return task == null ? null : Reservation.existing(task, task.getRequestDigest());
    }

    public List<Reservation> findRecoverableBefore(Date before, int limit) {
        List<StockWatchAnalysisTask> tasks = mapper.selectRecoverableBefore(before, Math.max(1, Math.min(limit, 100)));
        if (tasks == null) return Collections.emptyList();
        return tasks.stream().map(task -> Reservation.existing(task, task.getRequestDigest()))
                .collect(Collectors.toList());
    }

    @Transactional
    public Reservation claim(String submissionKey,
                             String purpose,
                             String symbol,
                             String market,
                             String periodType,
                             String observationKey,
                             String provider) {
        runtimeGate.assertReady();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schema", "stock-analysis-claim-v1");
        envelope.put("submissionKey", submissionKey);
        return reserve(submissionKey, sha256(submissionKey), purpose, symbol, market, periodType,
                observationKey, 0, provider, envelope);
    }

    @Transactional
    public void prepare(Reservation reservation,
                        String requestDigest,
                        int generation,
                        Map<String, Object> requestEnvelope) {
        runtimeGate.assertReady();
        if (reservation == null || !reservation.isNewSubmission() || !StringUtils.hasText(requestDigest)
                || requestEnvelope == null || requestEnvelope.isEmpty()) {
            throw new IllegalArgumentException("analysis_task_prepare_input_required");
        }
        StockWatchAnalysisTask task = reservation.getTask();
        String requestEnvelopeJson = JSON.toJSONString(requestEnvelope);
        int updated = mapper.prepare(task.getId(), value(task.getRowVersion()), requestDigest, generation,
                requestEnvelopeJson);
        if (updated != 1) {
            throw new IllegalStateException("analysis_task_prepare_conflict:" + task.getId());
        }
        task.setRequestDigest(requestDigest);
        task.setGeneration(generation);
        task.setRequestEnvelopeJson(requestEnvelopeJson);
        task.setRowVersion(value(task.getRowVersion()) + 1);
    }

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
        runtimeGate.assertReady();
        if (!StringUtils.hasText(submissionKey) || !StringUtils.hasText(requestDigest)) {
            throw new IllegalArgumentException("submission_key_and_request_digest_required");
        }
        StockWatchAnalysisTask existing = mapper.selectBySubmissionKey(submissionKey);
        if (existing != null) return Reservation.existing(existing, requestDigest);
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
            if (existing != null) return Reservation.existing(existing, requestDigest);
            throw duplicate;
        }
    }

    @Transactional
    public void markSubmitted(Reservation reservation, String provider) {
        runtimeGate.assertReady();
        if (reservation == null || !reservation.isNewSubmission()) return;
        StockWatchAnalysisTask task = reservation.getTask();
        int updated = mapper.markSubmitted(task.getId(), value(task.getRowVersion()), provider, new Date());
        if (updated != 1) throw new IllegalStateException("analysis_task_submission_state_conflict");
        task.setStatus("SUBMITTED");
        task.setProvider(provider);
        task.setRowVersion(value(task.getRowVersion()) + 1);
    }

    @Transactional
    public void markSuccess(Reservation reservation,
                            String rawResponse,
                            String contentStatus,
                            String completionReason,
                            String providerStatus) {
        completeNewSubmission(reservation, Collections.singletonList("SUBMITTED"), "SUCCESS",
                rawResponse, contentStatus, completionReason, providerStatus, null);
    }

    @Transactional
    public void markResponseReceived(Reservation reservation,
                                     String rawResponse,
                                     String contentStatus,
                                     String completionReason,
                                     String providerStatus,
                                     String bridgeTargetId,
                                     String conversationUrl) {
        Map<String, Object> responseEnvelope = new LinkedHashMap<>();
        responseEnvelope.put("schema", "stock-analysis-response-v1");
        responseEnvelope.put("submissionKey", reservation == null ? null : reservation.getTask().getSubmissionKey());
        responseEnvelope.put("requestDigest", reservation == null ? null : reservation.getTask().getRequestDigest());
        responseEnvelope.put("responseHash", StringUtils.hasText(rawResponse) ? sha256(rawResponse) : null);
        responseEnvelope.put("bridgeTargetId", bridgeTargetId);
        responseEnvelope.put("conversationUrl", conversationUrl);
        String responseEnvelopeJson = JSON.toJSONString(responseEnvelope);
        transition(reservation, Arrays.asList("SUBMITTED", "UNKNOWN"), "RESPONSE_RECEIVED",
                rawResponse, responseEnvelopeJson, contentStatus, completionReason, providerStatus, null);
    }

    @Transactional
    public void markProjectedSuccess(Reservation reservation) {
        transition(reservation, Arrays.asList("RESPONSE_RECEIVED", "PROJECTION_FAILED", "SUCCESS"),
                "PROJECTED_SUCCESS", reservation == null ? null : reservation.getTask().getRawResponse(),
                reservation == null ? null : reservation.getTask().getResponseEnvelopeJson(),
                reservation == null ? null : reservation.getTask().getContentStatus(),
                reservation == null ? null : reservation.getTask().getCompletionReason(),
                "PROJECTED", null);
    }

    @Transactional
    public void markProjectionFailed(Reservation reservation, String error) {
        transition(reservation, Arrays.asList("RESPONSE_RECEIVED", "PROJECTION_FAILED", "SUCCESS"),
                "PROJECTION_FAILED", reservation == null ? null : reservation.getTask().getRawResponse(),
                reservation == null ? null : reservation.getTask().getResponseEnvelopeJson(),
                reservation == null ? null : reservation.getTask().getContentStatus(),
                reservation == null ? null : reservation.getTask().getCompletionReason(),
                "PROJECTION_FAILED", error);
    }

    @Transactional
    public void markProjectionInvalid(Reservation reservation, String error) {
        transition(reservation, Arrays.asList("RESPONSE_RECEIVED", "PROJECTION_FAILED", "SUCCESS"),
                "PROJECTION_INVALID", reservation == null ? null : reservation.getTask().getRawResponse(),
                reservation == null ? null : reservation.getTask().getResponseEnvelopeJson(),
                reservation == null ? null : reservation.getTask().getContentStatus(),
                reservation == null ? null : reservation.getTask().getCompletionReason(),
                "PROJECTION_INVALID", error);
    }

    @Transactional
    public void markInvalid(Reservation reservation,
                            String rawResponse,
                            String contentStatus,
                            String completionReason,
                            String error) {
        completeNewSubmission(reservation, Collections.singletonList("SUBMITTED"), "INVALID_RESPONSE",
                rawResponse, contentStatus, completionReason, "INVALID", error);
    }

    @Transactional
    public void markUnknown(Reservation reservation, String rawResponse, String error) {
        completeNewSubmission(reservation, Collections.singletonList("SUBMITTED"), "UNKNOWN",
                rawResponse, null, null, "UNKNOWN", error);
    }

    @Transactional
    public void markFailed(Reservation reservation, String rawResponse, String error) {
        completeNewSubmission(reservation, Arrays.asList("RESERVED", "SUBMITTED"), "FAILED",
                rawResponse, null, null, "FAILED", error);
    }

    @Transactional
    public void markAbandoned(Reservation reservation, String terminalStatus, String error) {
        if (reservation == null || reservation.isNewSubmission()) {
            throw new IllegalArgumentException("analysis_task_existing_reservation_required");
        }
        String target = "UNKNOWN".equalsIgnoreCase(terminalStatus) ? "UNKNOWN" : "FAILED";
        transition(reservation, Arrays.asList("RESERVED", "SUBMITTED", "UNKNOWN"), target,
                reservation.getTask().getRawResponse(), reservation.getTask().getResponseEnvelopeJson(),
                reservation.getTask().getContentStatus(), reservation.getTask().getCompletionReason(),
                "ABANDONED_" + target, error);
    }

    private void completeNewSubmission(Reservation reservation,
                          java.util.List<String> fromStatuses,
                          String status,
                          String rawResponse,
                          String contentStatus,
                          String completionReason,
                          String providerStatus,
                          String error) {
        runtimeGate.assertReady();
        if (reservation == null || !reservation.isNewSubmission()) return;
        transition(reservation, fromStatuses, status, rawResponse, null, contentStatus,
                completionReason, providerStatus, error);
    }

    private void transition(Reservation reservation,
                          java.util.List<String> fromStatuses,
                          String status,
                          String rawResponse,
                          String responseEnvelopeJson,
                          String contentStatus,
                          String completionReason,
                          String providerStatus,
                          String error) {
        runtimeGate.assertReady();
        if (reservation == null) return;
        String responseHash = StringUtils.hasText(rawResponse) ? sha256(rawResponse) : null;
        String responseEnvelopeHash = StringUtils.hasText(responseEnvelopeJson) ? sha256(responseEnvelopeJson) : null;
        StockWatchAnalysisTask task = reservation.getTask();
        int updated = mapper.transition(task.getId(), value(task.getRowVersion()), fromStatuses, status,
                responseHash, responseEnvelopeHash, responseEnvelopeJson, rawResponse, contentStatus,
                completionReason, providerStatus, new Date(), error);
        if (updated != 1) {
            throw new IllegalStateException("analysis_task_transition_conflict:" + task.getStatus() + "->" + status);
        }
        reservation.getTask().setStatus(status);
        reservation.getTask().setResponseHash(responseHash);
        reservation.getTask().setResponseEnvelopeHash(responseEnvelopeHash);
        reservation.getTask().setResponseEnvelopeJson(responseEnvelopeJson);
        reservation.getTask().setRawResponse(rawResponse);
        reservation.getTask().setContentStatus(contentStatus);
        reservation.getTask().setCompletionReason(completionReason);
        reservation.getTask().setProviderStatus(providerStatus);
        reservation.getTask().setErrorMessage(error);
        reservation.getTask().setRowVersion(value(reservation.getTask().getRowVersion()) + 1);
    }

    public boolean shouldMarkUnknown(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof StockWatchBridgeHttpException) {
                return ((StockWatchBridgeHttpException) current).isProviderOutcomeUnknown();
            }
            current = current.getCause();
        }
        String value = error == null || error.getMessage() == null ? "" : error.getMessage().toLowerCase();
        return value.contains("timeout") || value.contains("timed out") || value.contains("unexpected end")
                || value.contains("connection reset") || value.contains("bridge http 500") || value.contains("max_inflight")
                || value.contains("provider_outcome_unknown");
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

    private String observationIdentityKey(String scope,
                                          String symbol,
                                          String market,
                                          String periodType,
                                          String observationKey) {
        return sha256(normalize(scope, true, "scope") + "\u0000"
                + normalize(market, true, "market") + "\u0000"
                + normalize(symbol, false, "symbol") + "\u0000"
                + normalize(periodType, true, "periodType") + "\u0000"
                + requiredText(observationKey, "observationKey"));
    }

    private String normalize(String value, boolean upper, String field) {
        String normalized = requiredText(value, field);
        return upper ? normalized.toUpperCase(java.util.Locale.ROOT) : normalized.toLowerCase(java.util.Locale.ROOT);
    }

    private String requiredText(String value, String field) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("analysis_identity_" + field + "_required");
        return value.trim();
    }

    public void verifyResponseIntegrity(Reservation reservation) {
        if (reservation == null || reservation.getTask() == null
                || !StringUtils.hasText(reservation.getTask().getRawResponse())
                || !StringUtils.hasText(reservation.getTask().getResponseHash())
                || !sha256(reservation.getTask().getRawResponse()).equals(reservation.getTask().getResponseHash())
                || !StringUtils.hasText(reservation.getTask().getResponseEnvelopeJson())
                || !StringUtils.hasText(reservation.getTask().getResponseEnvelopeHash())
                || !sha256(reservation.getTask().getResponseEnvelopeJson())
                .equals(reservation.getTask().getResponseEnvelopeHash())) {
            throw new IllegalStateException("analysis_response_hash_mismatch");
        }
    }

    public Map<String, Object> responseReceipt(Reservation reservation) {
        verifyResponseIntegrity(reservation);
        Map<String, Object> receipt;
        try {
            receipt = JSON.parseObject(reservation.getTask().getResponseEnvelopeJson(), LinkedHashMap.class);
        } catch (Exception invalid) {
            throw new IllegalStateException("analysis_response_envelope_invalid", invalid);
        }
        if (receipt == null
                || !"stock-analysis-response-v1".equals(String.valueOf(receipt.get("schema")))
                || !reservation.getTask().getSubmissionKey().equals(String.valueOf(receipt.get("submissionKey")))
                || !reservation.getTask().getRequestDigest().equals(String.valueOf(receipt.get("requestDigest")))
                || !reservation.getTask().getResponseHash().equals(String.valueOf(receipt.get("responseHash")))) {
            throw new IllegalStateException("analysis_response_envelope_fields_invalid");
        }
        return receipt;
    }

    private long value(Long raw) {
        return raw == null ? 0L : raw;
    }

    public static class Reservation {
        private final StockWatchAnalysisTask task;
        private final boolean newSubmission;
        private final boolean requestDigestMatch;

        private Reservation(StockWatchAnalysisTask task, boolean newSubmission, boolean requestDigestMatch) {
            this.task = task;
            this.newSubmission = newSubmission;
            this.requestDigestMatch = requestDigestMatch;
        }

        static Reservation created(StockWatchAnalysisTask task) {
            return new Reservation(task, true, true);
        }

        static Reservation existing(StockWatchAnalysisTask task, String requestedDigest) {
            return new Reservation(task, false, StringUtils.hasText(requestedDigest)
                    && requestedDigest.equals(task.getRequestDigest()));
        }

        public StockWatchAnalysisTask getTask() {
            return task;
        }

        public boolean isNewSubmission() {
            return newSubmission;
        }

        public boolean isRequestDigestMatch() {
            return requestDigestMatch;
        }

        public boolean hasReusableSuccess() {
            return !newSubmission && ("SUCCESS".equals(task.getStatus()) || "PROJECTED_SUCCESS".equals(task.getStatus()))
                    && StringUtils.hasText(task.getRawResponse());
        }

        public boolean hasReusableResponse() {
            return !newSubmission
                    && ("RESPONSE_RECEIVED".equals(task.getStatus()) || "PROJECTION_FAILED".equals(task.getStatus()))
                    && StringUtils.hasText(task.getRawResponse());
        }

        public boolean isProjectedSuccess() {
            return !newSubmission && "PROJECTED_SUCCESS".equals(task.getStatus());
        }

        public boolean isTerminalFailure() {
            return !newSubmission && ("INVALID_RESPONSE".equals(task.getStatus())
                    || "FAILED".equals(task.getStatus())
                    || "PROJECTION_INVALID".equals(task.getStatus()));
        }

        public boolean isInProgress() {
            return !newSubmission && ("RESERVED".equals(task.getStatus())
                    || "SUBMITTED".equals(task.getStatus())
                    || "UNKNOWN".equals(task.getStatus())
                    || "RESPONSE_RECEIVED".equals(task.getStatus())
                    || "PROJECTION_FAILED".equals(task.getStatus()));
        }

        public String refusalReason() {
            return "submission_already_exists:" + task.getStatus();
        }
    }
}
