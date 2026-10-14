package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeetingReviewedEvidenceServiceTest {
    private static JSONObject packet(String status, String requestId) {
        return JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                + "\"requestId\":\"" + requestId + "\",\"question\":\"联合国宪章签署年份\","
                + "\"status\":\"" + status + "\",\"audit\":{\"reviewCalls\":1,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"FOUND\",\"searchCount\":1,"
                + "\"ids\":[\"year\"],\"links\":[\"https://un.org/a\"]},"
                + "{\"phase\":\"review\",\"id\":\"year\",\"verdict\":\"PASS\","
                + "\"reviewerVerdict\":\"PASS\",\"searchCount\":1,"
                + "\"links\":[\"https://un.org/b\"]}]},"
                + "\"claims\":[{\"id\":\"year\",\"statement\":\"签署于1945年\","
                + "\"sourceUrls\":[\"https://un.org/a\"],\"reviewLinks\":[\"https://un.org/b\"],"
                + "\"reviewMethod\":\"TWO_ISOLATED_DEEPSEEK_SEARCHES\"}]}");
    }

    @Test
    void acceptsOnlyMatchingCrossCheckedPacketWithTwoSearchProofLinks() {
        MeetingReviewedEvidenceService.ReviewedEvidence accepted =
                MeetingReviewedEvidenceService.acceptPacket(packet("CROSS_CHECKED", "meeting-a"), "meeting-a");
        assertEquals(1, accepted.results.size());
        assertEquals("n8n-deepseek-reviewed", accepted.results.get(0).provider);
        assertEquals("https://un.org/a", accepted.results.get(0).url);
        assertEquals("N8N_PACKET_IDENTITY_INVALID", assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> MeetingReviewedEvidenceService.acceptPacket(packet("CROSS_CHECKED", "wrong"), "meeting-a")).getMessage());
        assertEquals("N8N_PACKET_IDENTITY_INVALID", assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> MeetingReviewedEvidenceService.acceptPacket(packet("CROSS_CHECKED", "meeting-a"),
                        "meeting-a", "another question")).getMessage());
        assertEquals("0.50", MeetingReviewedEvidenceService.acceptPacket(
                packet("PARTIAL", "meeting-a"), "meeting-a").confidenceScore.toPlainString());
        JSONObject missingAudit = packet("CROSS_CHECKED", "meeting-a");
        missingAudit.getJSONObject("audit").remove("trail");
        assertEquals("N8N_CLAIM_PROOF_INVALID", assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> MeetingReviewedEvidenceService.acceptPacket(missingAudit, "meeting-a")).getMessage());
        JSONObject terminalError = packet("CROSS_CHECKED", "meeting-a");
        terminalError.getJSONObject("audit").getJSONArray("trail").add(JSON.parseObject(
                "{\"phase\":\"review\",\"status\":\"ERROR\"}"));
        assertEquals("N8N_PACKET_AUDIT_ERROR", assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> MeetingReviewedEvidenceService.acceptPacket(terminalError, "meeting-a")).getMessage());
        JSONObject missingReviewLink = packet("CROSS_CHECKED", "meeting-a");
        missingReviewLink.getJSONArray("claims").getJSONObject(0).remove("reviewLinks");
        assertEquals("N8N_CLAIM_PROOF_INVALID", assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> MeetingReviewedEvidenceService.acceptPacket(missingReviewLink, "meeting-a")).getMessage());
        JSONObject laterContradicted = packet("CROSS_CHECKED", "meeting-a");
        laterContradicted.getJSONObject("audit").getJSONArray("trail").add(JSON.parseObject(
                "{\"phase\":\"review\",\"id\":\"year\",\"verdict\":\"REJECT\"}"));
        assertEquals("N8N_CLAIM_PROOF_INVALID", assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> MeetingReviewedEvidenceService.acceptPacket(laterContradicted, "meeting-a")).getMessage());
    }

    @Test
    void incompleteReviewAdmitsOnlyAuditedUnknownAsProvisional() {
        JSONObject limited = JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                + "\"requestId\":\"meeting-a\",\"question\":\"全球 token 用量\",\"status\":\"UNVERIFIED\","
                + "\"claims\":[],\"provisionalClaims\":[{\"id\":\"usage\","
                + "\"statement\":\"候选估计为100亿\",\"sourceUrls\":[\"https://example.com/a\"],"
                + "\"reviewVerdict\":\"UNKNOWN\",\"confidenceBand\":\"LOW\","
                + "\"confidenceScore\":0.25,\"confidenceBasis\":\"UNCALIBRATED_POLICY_SCORE\","
                + "\"limitation\":\"缺少独立来源\"}],\"audit\":{\"reviewCalls\":1,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"FOUND\",\"searchCount\":1,"
                + "\"ids\":[\"usage\"],\"links\":[\"https://example.com/a\"]},"
                + "{\"phase\":\"review\",\"id\":\"usage\",\"verdict\":\"UNKNOWN\","
                + "\"reviewerVerdict\":\"UNKNOWN\",\"searchCount\":1}]} }");
        MeetingReviewedEvidenceService.ReviewedEvidence accepted =
                MeetingReviewedEvidenceService.acceptPacket(limited, "meeting-a", "全球 token 用量");
        assertEquals("0.25", accepted.confidenceScore.toPlainString());
        assertEquals(1, accepted.results.size());
        assertEquals("n8n-deepseek-provisional", accepted.results.get(0).provider);

        JSONObject contradicted = JSON.parseObject(limited.toJSONString());
        contradicted.getJSONObject("audit").getJSONArray("trail").add(JSON.parseObject(
                "{\"phase\":\"review\",\"id\":\"usage\",\"verdict\":\"REJECT\"}"));
        assertEquals("N8N_PROVISIONAL_PROOF_INVALID", assertThrows(
                MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> MeetingReviewedEvidenceService.acceptPacket(contradicted, "meeting-a")).getMessage());

        JSONObject failedReview = JSON.parseObject(limited.toJSONString());
        failedReview.getJSONObject("audit").getJSONArray("trail").add(JSON.parseObject(
                "{\"phase\":\"review\",\"status\":\"ERROR\"}"));
        assertEquals("N8N_PACKET_AUDIT_ERROR", assertThrows(
                MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> MeetingReviewedEvidenceService.acceptPacket(failedReview, "meeting-a")).getMessage());

        JSONObject reversedAudit = JSON.parseObject(limited.toJSONString());
        JSONArray trail = reversedAudit.getJSONObject("audit").getJSONArray("trail");
        Object first = trail.remove(0);
        trail.add(first);
        assertEquals("N8N_PROVISIONAL_PROOF_INVALID", assertThrows(
                MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> MeetingReviewedEvidenceService.acceptPacket(reversedAudit, "meeting-a")).getMessage());
    }

    @Test
    void citationCaptureFailureProvidesNoFactsButUnknownOutcomeStillFailsClosed() {
        JSONObject citationGap = JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                + "\"requestId\":\"meeting-a\",\"question\":\"联合国宪章签署年份\","
                + "\"status\":\"ERROR\",\"claims\":[],\"provisionalClaims\":[],"
                + "\"audit\":{\"searchCalls\":1,\"reviewCalls\":0,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"ERROR\","
                + "\"reason\":\"CLAIM_CITATION_NOT_CAPTURED\"}]}}");
        MeetingReviewedEvidenceService.ReviewedEvidence unavailable =
                MeetingReviewedEvidenceService.acceptPacket(citationGap, "meeting-a", "联合国宪章签署年份");
        assertEquals(0, unavailable.results.size());
        assertEquals("0", unavailable.confidenceScore.toPlainString());

        JSONObject unknown = JSON.parseObject(citationGap.toJSONString());
        unknown.getJSONObject("audit").getJSONArray("trail").getJSONObject(0)
                .put("reason", "BRIDGE_PROVIDER_OUTCOME_UNKNOWN");
        assertEquals("N8N_OUTCOME_UNKNOWN", assertThrows(
                MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> MeetingReviewedEvidenceService.acceptPacket(unknown, "meeting-a")).getMessage());

        JSONObject fakeFact = JSON.parseObject(citationGap.toJSONString());
        fakeFact.getJSONArray("claims").add(JSON.parseObject("{\"id\":\"unsupported\"}"));
        assertEquals("N8N_PACKET_PROOF_MISSING", assertThrows(
                MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> MeetingReviewedEvidenceService.acceptPacket(fakeFact, "meeting-a")).getMessage());
    }

    @Test
    void webhookCarriesTokenAndAcceptsOnlyItsOwnReviewedRequest() throws Exception {
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<String> question = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst("X-Meeting-Evidence-Token"));
            JSONObject request = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            question.set(request.getString("question"));
            JSONObject response = packet("CROSS_CHECKED", request.getString("requestId"));
            byte[] bytes = response.toJSONString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try {
            MeetingReviewedEvidenceService service = new MeetingReviewedEvidenceService(true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook", "local-test-token");
            assertEquals(1, service.search("联合国宪章签署年份").results.size());
            assertEquals("local-test-token", token.get());
            assertEquals("联合国宪章签署年份", question.get());
        } finally {
            server.stop(0);
        }
    }
}
