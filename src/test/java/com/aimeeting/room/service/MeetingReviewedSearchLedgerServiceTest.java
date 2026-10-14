package com.aimeeting.room.service;

import com.aimeeting.room.entity.MeetingReviewedSearchGap;
import com.aimeeting.room.entity.MeetingReviewedSearchPlan;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class MeetingReviewedSearchLedgerServiceTest {
    private final long meetingId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;

    @Autowired MeetingReviewedSearchLedgerService ledger;

    @AfterEach
    void cleanup() {
        ledger.deleteByMeetingId(meetingId);
    }

    @Test
    void sameContextResumesOriginalPlanAndNextDayAppendsLineage() {
        MeetingReviewedSearchPlan first = ledger.savePlanIfAbsent(meetingId, 2, 17L, null,
                "2026-10-06", "{\"needs\":[{\"question\":\"first\"}]}");
        MeetingReviewedSearchPlan resumed = ledger.savePlanIfAbsent(meetingId, 2, 17L, null,
                "2026-10-06", "{\"needs\":[]}");
        assertEquals(first.getId(), resumed.getId());
        assertEquals("2026-10-06", resumed.getAsOf());
        assertEquals(first.getNormalizedPlanJson(), resumed.getNormalizedPlanJson());
        assertEquals(0L, resumed.getTargetQuestionId());
        MeetingReviewedSearchPlan nextDay = ledger.savePlanIfAbsent(meetingId, 2, 17L, null,
                "2026-10-07", "{\"needs\":[]}");
        assertNotEquals(first.getId(), nextDay.getId());
        assertEquals(first.getId(), nextDay.getSupersedesPlanId());
        assertEquals(nextDay.getId(), ledger.findPlan(meetingId, 2, 17L, null).getId());
        assertEquals(first.getId(), ledger.findPlanVersion(meetingId, 2, 17L, null,
                "2026-10-06", null).getId());

        MeetingReviewedSearchPlan targeted = ledger.savePlanIfAbsent(meetingId, 2, 17L, 409L,
                "2026-10-06", "{\"needs\":[]}");
        assertNotEquals(first.getId(), targeted.getId());
        assertEquals(409L, targeted.getTargetQuestionId());
    }

    @Test
    void newHumanContextCreatesVersionButUnresolvedRemoteOutcomeCannotBeBypassed() {
        MeetingReviewedSearchPlan first = ledger.savePlanIfAbsent(meetingId, 2, 17L, null,
                "2026-10-06", null, "{\"needs\":[]}");
        MeetingReviewedSearchPlan afterHuman = ledger.savePlanIfAbsent(meetingId, 2, 17L, null,
                "2026-10-06", 83L, "{\"needs\":[]}");
        assertEquals(first.getId(), afterHuman.getSupersedesPlanId());
        assertEquals(83L, afterHuman.getContextOpinionId());

        MeetingReviewedSearchGap unknown = ledger.findOrCreateGap(meetingId, 2,
                "2026-10-06", "需审核的原文问题");
        assertTrue(ledger.claimPendingGap(unknown.getId()));
        assertTrue(ledger.markUnknown(unknown.getId(), "N8N_OUTCOME_UNKNOWN"));
        assertEquals("PRIOR_SEARCH_OUTCOME_REQUIRES_REVIEW", assertThrows(
                MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> ledger.savePlanIfAbsent(meetingId, 2, 17L, null,
                        "2026-10-07", 83L, "{\"needs\":[]}")).getMessage());
    }

    @Test
    void exactQuestionAndDateShareOneRequestIdButDistinctIdentityGetsNewGap() {
        MeetingReviewedSearchGap first = ledger.findOrCreateGap(meetingId, 2, "2026-10-06", "全球 token 用量");
        MeetingReviewedSearchGap same = ledger.findOrCreateGap(meetingId, 2, "2026-10-06", "全球 token 用量");
        assertEquals(first.getId(), same.getId());
        assertEquals(first.getRequestId(), same.getRequestId());
        assertEquals(MeetingReviewedSearchLedgerService.PENDING, same.getStatus());
        assertNotEquals(first.getId(), ledger.findOrCreateGap(meetingId, 2,
                "2026-10-06", "全球 token 用量 ").getId());
        assertNotEquals(first.getId(), ledger.findOrCreateGap(meetingId, 2,
                "2026-10-07", "全球 token 用量").getId());
    }

    @Test
    void concurrentClaimHasOneWinnerAndUnknownNeverAutomaticallyReclaims() throws Exception {
        MeetingReviewedSearchGap gap = ledger.findOrCreateGap(meetingId, 1, "2026-10-06", "问题 A");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> claimAfterBarrier(gap.getId(), ready, release));
            Future<Boolean> b = pool.submit(() -> claimAfterBarrier(gap.getId(), ready, release));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(1, (a.get(5, TimeUnit.SECONDS) ? 1 : 0)
                    + (b.get(5, TimeUnit.SECONDS) ? 1 : 0));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(MeetingReviewedSearchLedgerService.IN_FLIGHT,
                ledger.findOrCreateGap(meetingId, 1, "2026-10-06", "问题 A").getStatus());
        assertFalse(ledger.claimPendingGap(gap.getId()));
        assertTrue(ledger.markUnknown(gap.getId(), "N8N_OUTCOME_UNKNOWN"));
        assertEquals(MeetingReviewedSearchLedgerService.UNKNOWN,
                ledger.findOrCreateGap(meetingId, 1, "2026-10-06", "问题 A").getStatus());
        assertFalse(ledger.claimPendingGap(gap.getId()));
        assertFalse(ledger.markRejected(gap.getId(), "LATE_RESPONSE"));
    }

    @Test
    void acceptedPacketRequiresPersistedRequestQuestionAndAsOf() {
        MeetingReviewedSearchGap gap = ledger.findOrCreateGap(meetingId, 1, "2026-10-06", "可核验问题");
        assertTrue(ledger.claimPendingGap(gap.getId()));
        JSONObject wrong = acceptedPacket("other-request", "可核验问题", "2026-10-06");
        assertEquals("N8N_PACKET_IDENTITY_INVALID", assertThrows(
                MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> ledger.markCrossChecked(gap.getId(), wrong.toJSONString())).getMessage());
        JSONObject wrongDate = acceptedPacket(gap.getRequestId(), "可核验问题", "2026-10-07");
        assertEquals("N8N_PACKET_ASOF_INVALID", assertThrows(
                MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> ledger.markCrossChecked(gap.getId(), wrongDate.toJSONString())).getMessage());
        assertEquals(MeetingReviewedSearchLedgerService.IN_FLIGHT,
                ledger.findGapById(gap.getId()).getStatus());

        JSONObject correct = acceptedPacket(gap.getRequestId(), "可核验问题", "2026-10-06");
        assertTrue(ledger.markCrossChecked(gap.getId(), correct.toJSONString()));
        MeetingReviewedSearchGap saved = ledger.findGapById(gap.getId());
        assertEquals(MeetingReviewedSearchLedgerService.CROSS_CHECKED, saved.getStatus());
        assertNotNull(saved.getPacketJson());
        assertFalse(ledger.claimPendingGap(gap.getId()));
        assertFalse(ledger.markUnknown(gap.getId(), "LATE_TIMEOUT"));
    }

    @Test
    void knownRejectionIsTerminalAndRawProviderTextIsNotPersisted() {
        MeetingReviewedSearchGap gap = ledger.findOrCreateGap(meetingId, 1, "2026-10-06", "问题 B");
        assertTrue(ledger.claimPendingGap(gap.getId()));
        assertTrue(ledger.markRejected(gap.getId(), "N8N_PACKET_IDENTITY_INVALID"));
        assertFalse(ledger.claimPendingGap(gap.getId()));
        assertEquals(MeetingReviewedSearchLedgerService.REJECTED, ledger.findGapById(gap.getId()).getStatus());

        MeetingReviewedSearchGap other = ledger.findOrCreateGap(meetingId, 1, "2026-10-06", "问题 C");
        assertTrue(ledger.claimPendingGap(other.getId()));
        assertTrue(ledger.markUnknown(other.getId(), "raw bearer secret would be rejected"));
        assertEquals("PROVIDER_OUTCOME_UNKNOWN", ledger.findGapById(other.getId()).getStatusReason());
    }

    @Test
    void completedUnverifiedSearchPersistsAsLowConfidenceAndCannotBePromoted() {
        MeetingReviewedSearchGap gap = ledger.findOrCreateGap(meetingId, 1, "2026-10-06", "仍需核验的问题");
        assertTrue(ledger.claimPendingGap(gap.getId()));
        JSONObject packet = acceptedPacket(gap.getRequestId(), "仍需核验的问题", "2026-10-06");
        packet.put("status", "UNVERIFIED");
        packet.put("claims", JSONObject.parseArray("[]"));
        packet.put("audit", JSONObject.parseObject("{\"searchCalls\":1,\"reviewCalls\":0,"
                + "\"trail\":[{\"phase\":\"search\",\"status\":\"UNKNOWN\",\"searchCount\":1}]}"));
        assertEquals("N8N_PACKET_STATUS_INVALID", assertThrows(
                MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> ledger.markCrossChecked(gap.getId(), packet.toJSONString())).getMessage());
        assertTrue(ledger.markLowConfidence(gap.getId(), packet.toJSONString()));
        MeetingReviewedSearchGap saved = ledger.findGapById(gap.getId());
        assertEquals(MeetingReviewedSearchLedgerService.LOW_CONFIDENCE, saved.getStatus());
        assertNotNull(saved.getPacketJson());
        assertFalse(ledger.claimPendingGap(gap.getId()));
    }

    private boolean claimAfterBarrier(Long id, CountDownLatch ready, CountDownLatch release)
            throws InterruptedException {
        ready.countDown();
        release.await();
        return ledger.claimPendingGap(id);
    }

    private JSONObject acceptedPacket(String requestId, String question, String asOf) {
        JSONObject packet = new JSONObject();
        packet.put("schemaVersion", "general.evidence-search.v1");
        packet.put("requestId", requestId);
        packet.put("question", question);
        packet.put("asOf", asOf);
        packet.put("status", "CROSS_CHECKED");
        packet.put("audit", JSONObject.parseObject("{\"reviewCalls\":1,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"FOUND\",\"searchCount\":1,"
                + "\"ids\":[\"fact\"],\"links\":[\"https://example.com/source\"]},"
                + "{\"phase\":\"review\",\"id\":\"fact\",\"verdict\":\"PASS\","
                + "\"reviewerVerdict\":\"PASS\",\"searchCount\":1,"
                + "\"links\":[\"https://example.com/review\"]}]}"));
        packet.put("claims", JSONObject.parseArray("[{\"id\":\"fact\",\"statement\":\"可核验\","
                + "\"sourceUrls\":[\"https://example.com/source\"],"
                + "\"reviewLinks\":[\"https://example.com/review\"],"
                + "\"reviewMethod\":\"TWO_ISOLATED_DEEPSEEK_SEARCHES\"}]"));
        return packet;
    }
}
