package com.aimeeting.room.service;

import com.aimeeting.room.service.ai.WebSearchService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** A strict boundary between n8n's reviewed search packet and meeting model input. */
@Service
public class MeetingReviewedEvidenceService {
    private static final MediaType JSON_TYPE = MediaType.get("application/json; charset=utf-8");
    private final boolean enabled;
    private final String endpoint;
    private final String token;
    private final OkHttpClient client;

    @Autowired(required = false)
    private MeetingCallLogService callLog;

    public MeetingReviewedEvidenceService(
            @Value("${AI_MEETING_REVIEWED_SEARCH_ENABLED:false}") boolean enabled,
            @Value("${AI_MEETING_REVIEWED_SEARCH_URL:http://127.0.0.1:5678/webhook/meeting-evidence-v1}") String endpoint,
            @Value("${AI_MEETING_REVIEWED_SEARCH_TOKEN:}") String token) {
        this.enabled = enabled;
        this.endpoint = endpoint;
        this.token = token;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(13, TimeUnit.MINUTES)
                .callTimeout(13, TimeUnit.MINUTES)
                .retryOnConnectionFailure(false)
                .build();
    }

    public boolean isEnabled() { return enabled; }

    public ReviewedEvidence search(String question) {
        return search("meeting-" + UUID.randomUUID(), question, LocalDate.now().toString());
    }

    /** The caller persists this identity before dispatch so an unknown outcome is never resubmitted. */
    public ReviewedEvidence search(String requestId, String question, String asOf) {
        return search(requestId, question, asOf, TimeUnit.MINUTES.toMillis(13));
    }

    public ReviewedEvidence search(String requestId, String question, String asOf, long timeoutMs) {
        long started = System.nanoTime();
        String status = "ERROR";
        try {
            ReviewedEvidence reviewed = searchOnce(requestId, question, asOf, timeoutMs);
            status = reviewed.packet == null ? "UNKNOWN" : reviewed.packet.getString("status");
            return reviewed;
        } catch (EvidenceRejected rejected) {
            status = rejected.getMessage();
            throw rejected;
        } finally {
            recordSearch(started, status);
        }
    }

    /** GEN-10 runs on the web account pool, so only time and outcome are recorded, never tokens. */
    private void recordSearch(long startedNanos, String status) {
        if (callLog == null) return;
        try {
            callLog.record(MeetingCallLogService.CHANNEL_GEN10, "gen10",
                    (System.nanoTime() - startedNanos) / 1_000_000L, status,
                    null, null, null, null, null);
        } catch (RuntimeException accountingFailure) {
            // Usage accounting must never change a reviewed search outcome.
        }
    }

    private ReviewedEvidence searchOnce(String requestId, String question, String asOf, long timeoutMs) {
        if (!enabled) throw new EvidenceRejected("REVIEWED_SEARCH_DISABLED");
        if (token == null || token.isBlank()) throw new EvidenceRejected("REVIEWED_SEARCH_TOKEN_MISSING");
        if (requestId == null || !requestId.matches("meeting-[A-Za-z0-9-]{1,80}")) {
            throw new EvidenceRejected("REVIEWED_SEARCH_REQUEST_ID_INVALID");
        }
        if (question == null || question.isBlank()) throw new EvidenceRejected("REVIEWED_SEARCH_QUESTION_MISSING");
        try {
            if (!LocalDate.parse(asOf).toString().equals(asOf)) {
                throw new EvidenceRejected("REVIEWED_SEARCH_ASOF_INVALID");
            }
        } catch (RuntimeException invalid) {
            throw new EvidenceRejected("REVIEWED_SEARCH_ASOF_INVALID", invalid);
        }
        if (timeoutMs < 1000 || timeoutMs > TimeUnit.MINUTES.toMillis(13)) {
            throw new EvidenceRejected("REVIEWED_SEARCH_TIMEOUT_INVALID");
        }
        JSONObject body = new JSONObject();
        body.put("requestId", requestId);
        body.put("question", question);
        body.put("asOf", asOf);
        try {
            Request request = new Request.Builder().url(endpoint)
                    .header("X-Meeting-Evidence-Token", token.trim())
                    .post(RequestBody.create(body.toJSONString(), JSON_TYPE)).build();
            OkHttpClient boundedClient = client.newBuilder()
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .build();
            try (Response response = boundedClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new EvidenceRejected("N8N_HTTP_" + response.code());
                }
                return acceptPacket(JSON.parseObject(response.body().string()), requestId, question);
            }
        } catch (EvidenceRejected e) {
            throw e;
        } catch (Exception e) {
            // A timed-out n8n execution may still have run the search. Never resubmit automatically.
            throw new EvidenceRejected("N8N_OUTCOME_UNKNOWN", e);
        }
    }

    static ReviewedEvidence acceptPacket(JSONObject packet, String requestId) {
        return acceptPacket(packet, requestId, null);
    }

    static ReviewedEvidence acceptPacket(JSONObject packet, String requestId, String question) {
        if (packet == null || !"general.evidence-search.v1".equals(packet.getString("schemaVersion"))
                || !requestId.equals(packet.getString("requestId"))
                || (question != null && !question.equals(packet.getString("question")))) {
            throw new EvidenceRejected("N8N_PACKET_IDENTITY_INVALID");
        }
        String status = packet.getString("status");
        boolean crossChecked = "CROSS_CHECKED".equals(status);
        boolean limited = "PARTIAL".equals(status) || "UNVERIFIED".equals(status);
        JSONArray claims = packet.getJSONArray("claims");
        JSONArray provisional = packet.getJSONArray("provisionalClaims");
        if (provisional == null) provisional = new JSONArray();
        JSONObject audit = packet.getJSONObject("audit");
        // A candidate without a captured citation is a deterministic zero-fact
        // result. Other ERROR packets may have an unknown provider outcome and
        // must retain the ledger's fail-closed behavior.
        if ("ERROR".equals(status)) {
            if (claims == null || !claims.isEmpty() || !provisional.isEmpty()
                    || audit == null || !hasTerminalAuditError(audit)) {
                throw new EvidenceRejected("N8N_PACKET_PROOF_MISSING");
            }
            String terminalReason = terminalAuditErrorReason(audit);
            if ("BRIDGE_TRANSPORT_OUTCOME_UNKNOWN".equals(terminalReason)
                    || "BRIDGE_PROVIDER_OUTCOME_UNKNOWN".equals(terminalReason)) {
                throw new EvidenceRejected("N8N_OUTCOME_UNKNOWN");
            }
            if ("CLAIM_CITATION_NOT_CAPTURED".equals(terminalReason)
                    && audit.getIntValue("searchCalls") >= 1) {
                return new ReviewedEvidence(packet, List.of(), BigDecimal.ZERO);
            }
            throw new EvidenceRejected("N8N_REVIEW_ERROR");
        }
        if (!crossChecked && !limited) throw new EvidenceRejected("N8N_REVIEW_" + status);
        if (claims == null || claims.size() > 3 || provisional.size() > 3 || audit == null
                || (crossChecked && (claims.isEmpty() || !provisional.isEmpty()))
                || ("PARTIAL".equals(status) && claims.isEmpty())
                || ("UNVERIFIED".equals(status) && !claims.isEmpty())
                || (!claims.isEmpty() && audit.getIntValue("reviewCalls") < 1)
                || (!provisional.isEmpty() && audit.getIntValue("reviewCalls") < 1)
                || (limited && !hasCompletedSearch(audit))) {
            throw new EvidenceRejected("N8N_PACKET_PROOF_MISSING");
        }
        if (hasTerminalAuditError(audit)) {
            throw new EvidenceRejected("N8N_PACKET_AUDIT_ERROR");
        }
        List<WebSearchService.SearchResult> results = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < claims.size(); i++) {
            JSONObject claim = claims.getJSONObject(i);
            if (claim == null || blank(claim.getString("id")) || blank(claim.getString("statement"))
                    || !seenIds.add(claim.getString("id"))
                    || !"TWO_ISOLATED_DEEPSEEK_SEARCHES".equals(claim.getString("reviewMethod"))
                    || !validUrls(claim.getJSONArray("sourceUrls"))
                    || !validUrls(claim.getJSONArray("reviewLinks"))
                    || !hasVerifiedAudit(audit, claim)) {
                throw new EvidenceRejected("N8N_CLAIM_PROOF_INVALID");
            }
            WebSearchService.SearchResult result = new WebSearchService.SearchResult();
            result.source = "n8n 双会话搜索审核：" + claim.getString("id");
            result.snippet = claim.getString("statement") + "；范围=" + claim.getString("scope")
                    + "；期间=" + claim.getString("period") + "；数值=" + claim.getString("value")
                    + "；单位=" + claim.getString("unit") + "；限制=" + claim.getString("qualification")
                    + "。审核方式：两次隔离的 DeepSeek 搜索，未读取链接原文。复核链接："
                    + claim.getJSONArray("reviewLinks").toJSONString();
            result.url = claim.getJSONArray("sourceUrls").getString(0);
            result.withTrace("n8n-deepseek-reviewed", "CROSS_CHECKED_SEARCH", packet.getString("question"), claim.toJSONString());
            results.add(result);
        }
        BigDecimal confidenceScore = crossChecked ? BigDecimal.ONE
                : "PARTIAL".equals(status) ? new BigDecimal("0.50") : BigDecimal.ZERO;
        for (int i = 0; i < provisional.size(); i++) {
            JSONObject claim = provisional.getJSONObject(i);
            BigDecimal score = claim == null ? null : claim.getBigDecimal("confidenceScore");
            if (claim == null || blank(claim.getString("id")) || blank(claim.getString("statement"))
                    || !seenIds.add(claim.getString("id"))
                    || !"UNKNOWN".equals(claim.getString("reviewVerdict"))
                    || !"LOW".equals(claim.getString("confidenceBand"))
                    || !"UNCALIBRATED_POLICY_SCORE".equals(claim.getString("confidenceBasis"))
                    || blank(claim.getString("limitation")) || !validUrls(claim.getJSONArray("sourceUrls"))
                    || score == null || score.compareTo(BigDecimal.ZERO) <= 0
                    || score.compareTo(new BigDecimal("0.50")) > 0
                    || !hasProvisionalAudit(audit, claim)) {
                throw new EvidenceRejected("N8N_PROVISIONAL_PROOF_INVALID");
            }
            confidenceScore = confidenceScore.compareTo(BigDecimal.ZERO) == 0
                    ? score : confidenceScore.min(score);
            WebSearchService.SearchResult result = new WebSearchService.SearchResult();
            result.source = "n8n 待核验候选：" + claim.getString("id")
                    + "（证据评分 " + score + "，非事实概率）";
            result.snippet = "【待核验，不能当作已证实事实】" + claim.getString("statement")
                    + "；范围=" + claim.getString("scope") + "；期间=" + claim.getString("period")
                    + "；数值=" + claim.getString("value") + "；单位=" + claim.getString("unit")
                    + "；限制=" + claim.getString("qualification")
                    + "；未核验原因=" + claim.getString("limitation");
            result.url = claim.getJSONArray("sourceUrls").getString(0);
            result.withTrace("n8n-deepseek-provisional", "LOW_CONFIDENCE_SEARCH",
                    packet.getString("question"), claim.toJSONString());
            results.add(result);
        }
        return new ReviewedEvidence(packet, results, confidenceScore);
    }

    private static boolean hasCompletedSearch(JSONObject audit) {
        JSONArray trail = audit.getJSONArray("trail");
        if (trail == null) return false;
        for (int i = 0; i < trail.size(); i++) {
            JSONObject event = trail.getJSONObject(i);
            if (event != null && "search".equals(event.getString("phase"))
                    && ("FOUND".equals(event.getString("status"))
                        || "UNKNOWN".equals(event.getString("status")))
                    && event.getIntValue("searchCount") >= 1) return true;
        }
        return false;
    }

    private static boolean hasTerminalAuditError(JSONObject audit) {
        JSONArray trail = audit.getJSONArray("trail");
        if (trail == null || trail.isEmpty()) return false;
        JSONObject last = trail.getJSONObject(trail.size() - 1);
        return last != null && "ERROR".equals(last.getString("status"));
    }

    private static String terminalAuditErrorReason(JSONObject audit) {
        JSONArray trail = audit.getJSONArray("trail");
        if (trail == null || trail.isEmpty()) return null;
        JSONObject last = trail.getJSONObject(trail.size() - 1);
        return last == null ? null : last.getString("reason");
    }

    private static boolean hasProvisionalAudit(JSONObject audit, JSONObject claim) {
        JSONArray trail = audit.getJSONArray("trail");
        if (trail == null) return false;
        String id = claim.getString("id");
        JSONArray urls = claim.getJSONArray("sourceUrls");
        boolean foundInInitialSearch = false;
        boolean independentlyReviewedUnknown = false;
        boolean contradicted = false;
        for (int i = 0; i < trail.size(); i++) {
            JSONObject event = trail.getJSONObject(i);
            if (event == null) continue;
            if ("search".equals(event.getString("phase")) && "FOUND".equals(event.getString("status"))
                    && event.getIntValue("searchCount") >= 1
                    && event.getJSONArray("ids") != null && event.getJSONArray("ids").contains(id)
                    && event.getJSONArray("links") != null
                    && urls.stream().allMatch(url -> event.getJSONArray("links").contains(url))) {
                foundInInitialSearch = true;
            }
            if (foundInInitialSearch && "review".equals(event.getString("phase"))
                    && id.equals(event.getString("id"))
                    && "UNKNOWN".equals(event.getString("verdict"))
                    && "UNKNOWN".equals(event.getString("reviewerVerdict"))
                    && event.getIntValue("searchCount") >= 1) independentlyReviewedUnknown = true;
            if ("review".equals(event.getString("phase")) && id.equals(event.getString("id"))
                    && "REJECT".equals(event.getString("verdict"))) contradicted = true;
        }
        return foundInInitialSearch && independentlyReviewedUnknown && !contradicted;
    }

    private static boolean hasVerifiedAudit(JSONObject audit, JSONObject claim) {
        JSONArray trail = audit.getJSONArray("trail");
        if (trail == null) return false;
        String id = claim.getString("id");
        JSONArray sourceUrls = claim.getJSONArray("sourceUrls");
        JSONArray reviewLinks = claim.getJSONArray("reviewLinks");
        boolean searched = false;
        boolean passed = false;
        boolean contradicted = false;
        for (int i = 0; i < trail.size(); i++) {
            JSONObject event = trail.getJSONObject(i);
            if (event == null) continue;
            if ("search".equals(event.getString("phase")) && "FOUND".equals(event.getString("status"))
                    && event.getIntValue("searchCount") >= 1
                    && event.getJSONArray("ids") != null && event.getJSONArray("ids").contains(id)
                    && event.getJSONArray("links") != null
                    && sourceUrls.stream().allMatch(url -> event.getJSONArray("links").contains(url))) {
                searched = true;
            }
            if ("review".equals(event.getString("phase"))
                    && id.equals(event.getString("id"))
                    && "REJECT".equals(event.getString("verdict"))) contradicted = true;
            if (searched && "review".equals(event.getString("phase"))
                    && id.equals(event.getString("id"))
                    && "PASS".equals(event.getString("verdict"))
                    && "PASS".equals(event.getString("reviewerVerdict"))
                    && event.getIntValue("searchCount") >= 1
                    && event.getJSONArray("links") != null
                    && reviewLinks.stream().allMatch(url -> event.getJSONArray("links").contains(url))) {
                passed = true;
            }
        }
        return passed && !contradicted;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static boolean validUrls(JSONArray values) {
        if (values == null || values.isEmpty()) return false;
        for (Object value : values) {
            if (!(value instanceof String) || !((String) value).matches("https?://[^\\s]+")) return false;
        }
        return true;
    }

    public static final class ReviewedEvidence {
        public final JSONObject packet;
        public final List<WebSearchService.SearchResult> results;
        /** Policy score for source evidence, never a calibrated probability or model confidence. */
        public final BigDecimal confidenceScore;

        private ReviewedEvidence(JSONObject packet, List<WebSearchService.SearchResult> results,
                                 BigDecimal confidenceScore) {
            this.packet = packet;
            this.results = results;
            this.confidenceScore = confidenceScore;
        }
    }

    public static final class EvidenceRejected extends RuntimeException {
        public EvidenceRejected(String reason) { super(reason); }
        public EvidenceRejected(String reason, Throwable cause) { super(reason, cause); }
    }
}
