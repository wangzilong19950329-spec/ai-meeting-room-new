package com.aimeeting.room.service;

import com.aimeeting.room.config.ModelRegistryStore;
import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.StockWatchItemMapper;
import com.aimeeting.room.dto.ChatResult;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.MeetingReviewedSearchGap;
import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.service.ai.WebSearchService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises the real SQL ledger across fresh debate-service instances. */
@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class MeetingDebateReviewedLedgerIntegrationTest {
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Shanghai");
    private final long meetingId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;

    @Autowired private MeetingReviewedSearchLedgerService ledger;

    @AfterEach
    void cleanup() {
        ledger.deleteByMeetingId(meetingId);
    }

    @Test
    void successfulSearchAndPlanSurviveInferenceFailureWithoutAnotherN8nPost() {
        Fixture fixture = new Fixture();
        fixture.reviewSuccess();
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(null, validOpinion());

        assertTrue(fixture.newDebateService().executeDebateRound(meetingId, 1).isEmpty());
        assertEquals(MeetingStatusEnum.PAUSED.getCode(), fixture.room.getStatus());
        assertEquals(0, fixture.inserted.size());
        MeetingReviewedSearchGap saved = fixture.savedGap();
        assertEquals(MeetingReviewedSearchLedgerService.CROSS_CHECKED, saved.getStatus());
        assertNotNull(saved.getPacketJson());
        assertEquals(saved.getRequestId(), JSON.parseObject(saved.getPacketJson()).getString("requestId"));
        assertNotNull(ledger.findPlan(meetingId, 1, fixture.member.getId(), null));

        // Simulate an explicit resume and a process replacement. No in-memory cache crosses this boundary.
        fixture.room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        List<AiMeetingOpinion> resumed = fixture.newDebateService().executeDebateRound(meetingId, 1);

        assertEquals(1, resumed.size());
        assertEquals("CROSS_CHECKED", JSON.parseObject(resumed.get(0).getEvidence())
                .getString("reviewedSearchStatus"));
        assertEquals(1, fixture.inserted.size());
        verify(fixture.orchestrator, times(1))
                .callModelStrict(eq("deepseek-chat"), anyString(), anyString(), eq(500));
        verify(fixture.reviewed, times(1)).search(eq(saved.getRequestId()),
                eq(saved.getExactQuestion()), eq(saved.getAsOf()), anyLong());
    }

    @Test
    void unknownN8nOutcomeIsTerminalAcrossResumeAndDoesNotReachInference() {
        Fixture fixture = new Fixture();
        when(fixture.reviewed.search(anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new MeetingReviewedEvidenceService.EvidenceRejected("N8N_OUTCOME_UNKNOWN"));

        assertTrue(fixture.newDebateService().executeDebateRound(meetingId, 1).isEmpty());
        MeetingReviewedSearchGap saved = fixture.savedGap();
        assertEquals(MeetingReviewedSearchLedgerService.UNKNOWN, saved.getStatus());
        fixture.room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());

        assertTrue(fixture.newDebateService().executeDebateRound(meetingId, 1).isEmpty());
        assertEquals(MeetingReviewedSearchLedgerService.UNKNOWN, fixture.savedGap().getStatus());
        verify(fixture.reviewed, times(1)).search(anyString(), anyString(), anyString(), anyLong());
        verify(fixture.orchestrator, times(1))
                .callModelStrict(eq("deepseek-chat"), anyString(), anyString(), eq(500));
        verify(fixture.orchestrator, never()).callModelExStrict(anyString(), anyString(), anyString());
        verify(fixture.opinionMapper, never()).insert(any());
    }

    @Test
    void pauseDuringSearchBlocksTheOpinionAlthoughThePacketWasAccepted() {
        Fixture fixture = new Fixture();
        when(fixture.reviewed.search(anyString(), anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            fixture.room.setStatus(MeetingStatusEnum.PAUSED.getCode());
            return acceptedPacket(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2));
        });

        assertTrue(fixture.newDebateService().executeDebateRound(meetingId, 1).isEmpty());

        assertEquals(MeetingStatusEnum.PAUSED.getCode(), fixture.room.getStatus());
        assertEquals(MeetingReviewedSearchLedgerService.CROSS_CHECKED, fixture.savedGap().getStatus());
        verify(fixture.orchestrator, never()).callModelExStrict(anyString(), anyString(), anyString());
        verify(fixture.opinionMapper, never()).insert(any());
    }

    @Test
    void nextDayResumeCreatesNewPlanAndSearchWithoutDeletingYesterdayPacket() {
        Fixture fixture = new Fixture();
        fixture.reviewSuccess();
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(null, validOpinion());

        assertTrue(fixture.newDebateService().executeDebateRound(meetingId, 1).isEmpty());
        MeetingReviewedSearchGap oldGap = fixture.savedGap();
        Long oldPlanId = ledger.findPlan(meetingId, 1, fixture.member.getId(), null).getId();
        fixture.room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        fixture.asOf.set(LocalDate.parse(oldGap.getAsOf()).plusDays(1).toString());

        List<AiMeetingOpinion> resumed = fixture.newDebateService().executeDebateRound(meetingId, 1);

        assertEquals(1, resumed.size());
        assertEquals(2, ledger.listPlansByMeetingId(meetingId).size());
        assertEquals(oldPlanId, ledger.findPlan(meetingId, 1,
                fixture.member.getId(), null).getSupersedesPlanId());
        assertEquals(MeetingReviewedSearchLedgerService.CROSS_CHECKED,
                ledger.findGapById(oldGap.getId()).getStatus());
        assertNotNull(ledger.findGapById(oldGap.getId()).getPacketJson());
        assertEquals(2, ledger.listGapsByMeetingId(meetingId).size());
        verify(fixture.reviewed, times(2)).search(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void pauseAfterFirstOfTwoGapsPreventsSecondProviderDispatch() {
        Fixture fixture = new Fixture();
        fixture.twoNeeds();
        when(fixture.reviewed.search(anyString(), anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            fixture.room.setStatus(MeetingStatusEnum.PAUSED.getCode());
            return acceptedPacket(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2));
        });

        assertTrue(fixture.newDebateService().executeDebateRound(meetingId, 1).isEmpty());

        assertEquals(1, ledger.listGapsByMeetingId(meetingId).size());
        assertEquals(MeetingReviewedSearchLedgerService.CROSS_CHECKED,
                ledger.listGapsByMeetingId(meetingId).get(0).getStatus());
        verify(fixture.reviewed, times(1)).search(anyString(), anyString(), anyString(), anyLong());
        verify(fixture.orchestrator, never()).callModelExStrict(anyString(), anyString(), anyString());
    }

    @Test
    void humanMessageDuringSearchBlocksStaleInferenceAndResumeReplansAgainstNewContext() {
        Fixture fixture = new Fixture();
        when(fixture.reviewed.search(anyString(), anyString(), anyString(), anyLong())).thenAnswer(invocation -> {
            AiMeetingOpinion human = new AiMeetingOpinion();
            human.setId(501L);
            human.setMeetingId(meetingId);
            human.setRoundNumber(1);
            human.setMemberId(0L);
            human.setContent("请同时核对统计口径");
            fixture.inserted.add(human);
            return acceptedPacket(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2));
        });

        assertTrue(fixture.newDebateService().executeDebateRound(meetingId, 1).isEmpty());
        assertEquals(MeetingStatusEnum.PAUSED.getCode(), fixture.room.getStatus());
        assertEquals(1, fixture.inserted.size());
        assertEquals(1, ledger.listPlansByMeetingId(meetingId).size());
        verify(fixture.orchestrator, never()).callModelExStrict(anyString(), anyString(), anyString());

        fixture.room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        List<AiMeetingOpinion> resumed = fixture.newDebateService().executeDebateRound(meetingId, 1);
        assertEquals(1, resumed.size());
        assertEquals(2, ledger.listPlansByMeetingId(meetingId).size());
        assertEquals(501L, ledger.findPlan(meetingId, 1,
                fixture.member.getId(), null).getContextOpinionId());
        assertEquals(501L, JSON.parseObject(resumed.get(0).getEvidence())
                .getLong("reviewedSearchContextOpinionId"));
        verify(fixture.reviewed, times(1)).search(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void memberLocalReviewRejectionRecordsPlanAndFailedGapWithoutFabricatingOpinion() {
        Fixture fixture = new Fixture();
        fixture.skipEnabled = true;
        when(fixture.reviewed.search(anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new MeetingReviewedEvidenceService.EvidenceRejected("N8N_REVIEW_UNVERIFIED"));

        assertTrue(fixture.newDebateService().executeDebateRound(meetingId, 1).isEmpty());

        ArgumentCaptor<MeetingReviewedTurnSkip> skip =
                ArgumentCaptor.forClass(MeetingReviewedTurnSkip.class);
        verify(fixture.skipService).recordIfCurrent(skip.capture(),
                eq(MeetingStatusEnum.IN_PROGRESS.getCode()), nullable(Long.class));
        assertEquals("N8N_REVIEW_UNVERIFIED", skip.getValue().getReasonCode());
        assertNotNull(skip.getValue().getPlanId());
        assertNotNull(skip.getValue().getGapId());
        assertEquals(MeetingReviewedSearchLedgerService.REJECTED,
                ledger.findGapById(skip.getValue().getGapId()).getStatus());
        assertEquals(MeetingStatusEnum.IN_PROGRESS.getCode(), fixture.room.getStatus());
        assertEquals(0, fixture.inserted.size());
    }

    @Test
    void terminalSecondGapFailureKeepsFirstLedgerFactAndAllowsCautiousSpeech() {
        Fixture fixture = new Fixture();
        fixture.twoNeeds();
        when(fixture.reviewed.search(anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    String question = invocation.getArgument(1);
                    if (question.contains("事实 B")) {
                        throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_REVIEW_ERROR");
                    }
                    return acceptedPacket(invocation.getArgument(0), question,
                            invocation.getArgument(2));
                });

        List<AiMeetingOpinion> opinions = fixture.newDebateService().executeDebateRound(meetingId, 1);

        assertEquals(1, opinions.size());
        assertEquals(MeetingStatusEnum.IN_PROGRESS.getCode(), fixture.room.getStatus());
        JSONObject evidence = JSON.parseObject(opinions.get(0).getEvidence());
        assertEquals("LOW_CONFIDENCE", evidence.getString("reviewedSearchStatus"));
        assertEquals("UNVERIFIED_GAP", evidence.getString("reviewedSearchCoverageStatus"));
        assertEquals(1, evidence.getJSONArray("reviewedSearchPackets").size());
        assertEquals(1, evidence.getJSONObject("reviewedSearchDecision")
                .getIntValue("unverifiedGapCount"));
        assertEquals(1, ledger.listGapsByMeetingId(meetingId).stream()
                .filter(gap -> MeetingReviewedSearchLedgerService.CROSS_CHECKED.equals(gap.getStatus()))
                .count());
        assertEquals(1, ledger.listGapsByMeetingId(meetingId).stream()
                .filter(gap -> MeetingReviewedSearchLedgerService.REJECTED.equals(gap.getStatus()))
                .count());
        verify(fixture.reviewed, times(2)).search(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void completedButUnverifiedSearchLetsMemberSpeakAndSurvivesResume() {
        Fixture fixture = new Fixture();
        fixture.skipEnabled = true;
        when(fixture.reviewed.search(anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> unverifiedPacket(invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2)));
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(null, validOpinion());

        assertTrue(fixture.newDebateService().executeDebateRound(meetingId, 1).isEmpty());
        MeetingReviewedSearchGap saved = fixture.savedGap();
        assertEquals(MeetingReviewedSearchLedgerService.LOW_CONFIDENCE, saved.getStatus());
        fixture.room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());

        List<AiMeetingOpinion> resumed = fixture.newDebateService().executeDebateRound(meetingId, 1);
        assertEquals(1, resumed.size());
        JSONObject evidence = JSON.parseObject(resumed.get(0).getEvidence());
        assertEquals("LOW_CONFIDENCE", evidence.getString("reviewedSearchStatus"));
        assertEquals("0", evidence.getBigDecimal("reviewedSearchConfidenceScore").toPlainString());
        assertTrue(resumed.get(0).getContent().contains("资料未充分交叉核验"));
        verify(fixture.reviewed, times(1)).search(anyString(), anyString(), anyString(), anyLong());
        ArgumentCaptor<MeetingReviewedTurnSkip> skips = ArgumentCaptor.forClass(MeetingReviewedTurnSkip.class);
        verify(fixture.skipService, times(1)).recordIfCurrent(skips.capture(), anyInt(), nullable(Long.class));
        assertEquals("MODEL_OUTPUT_UNAVAILABLE", skips.getValue().getReasonCode());
    }

    @Test
    void provisionalOnlySearchMarksGapUnverifiedWhileMemberStillSpeaks() {
        Fixture fixture = new Fixture();
        when(fixture.reviewed.search(anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> provisionalOnlyPacket(invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2)));

        List<AiMeetingOpinion> opinions = fixture.newDebateService().executeDebateRound(meetingId, 1);

        assertEquals(1, opinions.size());
        JSONObject evidence = JSON.parseObject(opinions.get(0).getEvidence());
        assertEquals("LOW_CONFIDENCE", evidence.getString("reviewedSearchStatus"));
        assertEquals("UNVERIFIED_GAP", evidence.getString("reviewedSearchCoverageStatus"));
        assertEquals(1, evidence.getJSONObject("reviewedSearchDecision")
                .getIntValue("unverifiedGapCount"));
        assertEquals("n8n-deepseek-provisional",
                evidence.getJSONArray("results").getJSONObject(0).getString("provider"));
    }

    @Test
    void provisionalNumberIsPublishedWithVisibleLowConfidenceLabel() {
        Fixture fixture = new Fixture();
        fixture.skipEnabled = true;
        when(fixture.reviewed.search(anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> provisionalOnlyPacket(invocation.getArgument(0),
                        invocation.getArgument(1), invocation.getArgument(2)));
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n"
                        + "该指标已经达到100亿元。"));

        List<AiMeetingOpinion> opinions = fixture.newDebateService().executeDebateRound(meetingId, 1);

        assertEquals(1, opinions.size());
        assertTrue(opinions.get(0).getContent().contains("100亿元〔低置信〕"), opinions.get(0).getContent());
        JSONObject numbers = JSON.parseObject(opinions.get(0).getEvidence()).getJSONObject("reviewedNumberAudit");
        assertEquals(1, numbers.getIntValue("lowConfidenceCount"));
        assertEquals(0, numbers.getIntValue("verifiedCount"));
        verify(fixture.skipService, never()).recordIfCurrent(any(), anyInt(), nullable(Long.class));
    }

    @Test
    void unitlessNumberMissingFromEvidenceIsRewrittenOnceThenSkipped() {
        Fixture fixture = new Fixture();
        fixture.skipEnabled = true;
        fixture.reviewSuccessWith("该指数市盈率为25");
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(speech("该指数PE 30，估值偏高。"));

        assertTrue(fixture.newDebateService().executeDebateRound(meetingId, 1).isEmpty());

        assertTrue(fixture.inserted.isEmpty());
        verify(fixture.orchestrator, times(2)).callModelExStrict(eq("deepseek-chat"), anyString(), anyString());
        ArgumentCaptor<MeetingReviewedTurnSkip> skips = ArgumentCaptor.forClass(MeetingReviewedTurnSkip.class);
        verify(fixture.skipService).recordIfCurrent(skips.capture(), anyInt(), nullable(Long.class));
        assertEquals("MODEL_UNSUPPORTED_CLAIMS", skips.getValue().getReasonCode());
    }

    @Test
    void rewriteThatOnlyUsesEvidenceNumbersIsPublished() {
        Fixture fixture = new Fixture();
        fixture.reviewSuccessWith("该指数市盈率为25");
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(speech("该指数PE 30，估值偏高。"), speech("该指数PE 25，估值中性。"));

        List<AiMeetingOpinion> opinions = fixture.newDebateService().executeDebateRound(meetingId, 1);

        assertEquals(1, opinions.size());
        assertEquals("该指数PE 25，估值中性。", opinions.get(0).getContent());
        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(fixture.orchestrator, times(2))
                .callModelExStrict(eq("deepseek-chat"), anyString(), prompts.capture());
        assertTrue(prompts.getAllValues().get(1).contains("30"), prompts.getAllValues().get(1));
        JSONObject numbers = JSON.parseObject(opinions.get(0).getEvidence()).getJSONObject("reviewedNumberAudit");
        assertEquals(1, numbers.getIntValue("verifiedCount"));
        assertEquals(1, numbers.getIntValue("rewriteCount"));
    }

    @Test
    void unitlessAndScaledNumbersFoundInVerifiedEvidencePublishWithoutRewrite() {
        Fixture fixture = new Fixture();
        fixture.reviewSuccessWith("该指数市盈率为25，当日成交量32000手");
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(speech("该指数PE 25，成交3.2万手。"));

        List<AiMeetingOpinion> opinions = fixture.newDebateService().executeDebateRound(meetingId, 1);

        assertEquals(1, opinions.size());
        assertEquals("该指数PE 25，成交3.2万手。", opinions.get(0).getContent());
        verify(fixture.orchestrator, times(1)).callModelExStrict(eq("deepseek-chat"), anyString(), anyString());
        JSONObject numbers = JSON.parseObject(opinions.get(0).getEvidence()).getJSONObject("reviewedNumberAudit");
        assertEquals(2, numbers.getIntValue("verifiedCount"));
    }

    @Test
    void datesRoundsListMarkersDurationsAndCodesAreNotTreatedAsClaims() {
        Fixture fixture = new Fixture();
        fixture.reviewSuccess();
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(speech("第1轮判断：截至2026年10月17日，未来3个月、近20个交易日、"
                        + "MA20 与 300750.sz 只是时间、序号或代码。\n1、结论维持不变。"));

        List<AiMeetingOpinion> opinions = fixture.newDebateService().executeDebateRound(meetingId, 1);

        assertEquals(1, opinions.size());
        verify(fixture.orchestrator, times(1)).callModelExStrict(eq("deepseek-chat"), anyString(), anyString());
        JSONObject numbers = JSON.parseObject(opinions.get(0).getEvidence()).getJSONObject("reviewedNumberAudit");
        assertEquals(0, numbers.getIntValue("checkedCount"));
    }

    private static ChatResult speech(String reply) {
        return new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n" + reply);
    }

    private static ChatResult validOpinion() {
        return new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n本轮观点。");
    }

    private static MeetingReviewedEvidenceService.ReviewedEvidence acceptedPacket(
            String requestId, String question, String asOf) {
        return acceptedPacket(requestId, question, asOf, "经交叉搜索核验的公开事实");
    }

    private static MeetingReviewedEvidenceService.ReviewedEvidence acceptedPacket(
            String requestId, String question, String asOf, String statement) {
        JSONObject packet = JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                + "\"status\":\"CROSS_CHECKED\",\"audit\":{\"reviewCalls\":1,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"FOUND\",\"searchCount\":1,"
                + "\"ids\":[\"fact\"],\"links\":[\"https://example.org/source\"]},"
                + "{\"phase\":\"review\",\"id\":\"fact\",\"verdict\":\"PASS\","
                + "\"reviewerVerdict\":\"PASS\",\"searchCount\":1,"
                + "\"links\":[\"https://example.org/review\"]}]},"
                + "\"claims\":[{\"id\":\"fact\","
                + "\"sourceUrls\":[\"https://example.org/source\"],"
                + "\"reviewLinks\":[\"https://example.org/review\"],"
                + "\"reviewMethod\":\"TWO_ISOLATED_DEEPSEEK_SEARCHES\"}]}");
        packet.getJSONArray("claims").getJSONObject(0).put("statement", statement);
        packet.put("requestId", requestId);
        packet.put("question", question);
        packet.put("asOf", asOf);
        return MeetingReviewedEvidenceService.acceptPacket(packet, requestId, question);
    }

    private static MeetingReviewedEvidenceService.ReviewedEvidence unverifiedPacket(
            String requestId, String question, String asOf) {
        JSONObject packet = JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                + "\"status\":\"UNVERIFIED\",\"claims\":[],\"provisionalClaims\":[],"
                + "\"audit\":{\"searchCalls\":1,\"reviewCalls\":0,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"UNKNOWN\",\"searchCount\":1}]}}");
        packet.put("requestId", requestId);
        packet.put("question", question);
        packet.put("asOf", asOf);
        return MeetingReviewedEvidenceService.acceptPacket(packet, requestId, question);
    }

    private static MeetingReviewedEvidenceService.ReviewedEvidence provisionalOnlyPacket(
            String requestId, String question, String asOf) {
        JSONObject packet = JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                + "\"status\":\"UNVERIFIED\",\"claims\":[],"
                + "\"provisionalClaims\":[{\"id\":\"candidate\","
                + "\"statement\":\"待核验的候选数字100亿\","
                + "\"sourceUrls\":[\"https://example.org/candidate\"],"
                + "\"reviewVerdict\":\"UNKNOWN\",\"confidenceBand\":\"LOW\","
                + "\"confidenceScore\":0.25,"
                + "\"confidenceBasis\":\"UNCALIBRATED_POLICY_SCORE\","
                + "\"limitation\":\"缺少独立来源\"}],"
                + "\"audit\":{\"searchCalls\":1,\"reviewCalls\":1,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"FOUND\",\"searchCount\":1,"
                + "\"ids\":[\"candidate\"],\"links\":[\"https://example.org/candidate\"]},"
                + "{\"phase\":\"review\",\"id\":\"candidate\",\"verdict\":\"UNKNOWN\","
                + "\"reviewerVerdict\":\"UNKNOWN\",\"searchCount\":1}]}}");
        packet.put("requestId", requestId);
        packet.put("question", question);
        packet.put("asOf", asOf);
        return MeetingReviewedEvidenceService.acceptPacket(packet, requestId, question);
    }

    private final class Fixture {
        private final AiMeetingRoom room = new AiMeetingRoom();
        private final AiMeetingMember member = new AiMeetingMember();
        private final AiMeetingRoomMapper roomMapper = mock(AiMeetingRoomMapper.class);
        private final AiMeetingMemberMapper memberMapper = mock(AiMeetingMemberMapper.class);
        private final AiMeetingOpinionMapper opinionMapper = mock(AiMeetingOpinionMapper.class);
        private final MeetingWorkflowService workflow = mock(MeetingWorkflowService.class);
        private final MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        private final MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        private final WebSearchService webSearch = mock(WebSearchService.class);
        private final List<AiMeetingOpinion> inserted = new ArrayList<>();
        private final MeetingReviewedTurnSkipService skipService = mock(MeetingReviewedTurnSkipService.class);
        private boolean skipEnabled;
        private final AtomicReference<String> asOf = new AtomicReference<>(
                LocalDate.now(MARKET_ZONE).toString());

        private Fixture() {
            room.setId(meetingId);
            room.setTitle("通用搜索审核测试");
            room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
            room.setCurrentRound(1);
            member.setId(13L);
            member.setMeetingId(meetingId);
            member.setMemberName("验证议员");
            member.setModelType("deepseek-chat");
            member.setPersona("检验数据出处和推理边界");
            when(roomMapper.selectById(meetingId)).thenReturn(room);
            when(roomMapper.updateStatus(eq(meetingId), anyInt())).thenAnswer(invocation -> {
                room.setStatus(invocation.getArgument(1));
                return 1;
            });
            when(memberMapper.selectByMeetingId(meetingId)).thenReturn(List.of(member));
            when(workflow.resolveMembersForCurrentStage(eq(meetingId), anyList()))
                    .thenReturn(List.of(member));
            when(opinionMapper.selectByMeetingIdAndRound(meetingId, 1))
                    .thenAnswer(invocation -> inserted.stream()
                            .filter(opinion -> Integer.valueOf(1).equals(opinion.getRoundNumber()))
                            .collect(java.util.stream.Collectors.toList()));
            when(opinionMapper.selectByMeetingId(meetingId))
                    .thenAnswer(invocation -> new ArrayList<>(inserted));
            when(opinionMapper.insert(any())).thenAnswer(invocation -> {
                AiMeetingOpinion opinion = invocation.getArgument(0);
                opinion.setId(100L + inserted.size());
                inserted.add(opinion);
                return 1;
            });
            when(reviewed.isEnabled()).thenReturn(true);
            when(orchestrator.buildReviewedEvidenceNeedsPrompt(meetingId, member.getId(), 1))
                    .thenReturn("为本轮独立判断提出资料需求");
            when(orchestrator.callModelStrict(eq("deepseek-chat"), anyString(), anyString(), eq(500)))
                    .thenReturn("{\"need\":true,\"needs\":[{\"id\":\"public-fact\","
                            + "\"question\":\"截至2026年10月中国2025年GDP总量是多少？\","
                            + "\"eastmoneyFields\":[],\"webSearch\":true,\"sourceFocus\":[]}]}");
            when(orchestrator.buildDebateContext(eq(meetingId), anyLong(), eq(1), nullable(String.class)))
                    .thenReturn("审核后的资料包");
            when(orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                    .thenReturn(validOpinion());
            when(webSearch.formatResultsForPrompt(anyList())).thenReturn("审核后的资料包");
        }

        private MeetingDebateService newDebateService() {
            MeetingDebateService debate = new MeetingDebateService();
            ReflectionTestUtils.setField(debate, "roomMapper", roomMapper);
            ReflectionTestUtils.setField(debate, "memberMapper", memberMapper);
            ReflectionTestUtils.setField(debate, "opinionMapper", opinionMapper);
            ReflectionTestUtils.setField(debate, "workflowService", workflow);
            ReflectionTestUtils.setField(debate, "reviewedEvidenceService", reviewed);
            ReflectionTestUtils.setField(debate, "reviewedSearchLedgerService", ledger);
            ReflectionTestUtils.setField(debate, "orchestrator", orchestrator);
            ReflectionTestUtils.setField(debate, "webSearch", webSearch);
            ReflectionTestUtils.setField(debate, "eastmoneyMarketDataService",
                    mock(EastmoneyMarketDataService.class));
            ReflectionTestUtils.setField(debate, "stockWatchItemMapper", mock(StockWatchItemMapper.class));
            ReflectionTestUtils.setField(debate, "modelRegistryStore", mock(ModelRegistryStore.class));
            if (skipEnabled) ReflectionTestUtils.setField(debate, "reviewedTurnSkipService", skipService);
            ReflectionTestUtils.setField(debate, "reviewedAsOfSupplier",
                    (java.util.function.Supplier<String>) asOf::get);
            return debate;
        }

        private void twoNeeds() {
            when(orchestrator.callModelStrict(eq("deepseek-chat"), anyString(), anyString(), eq(500)))
                    .thenReturn("{\"need\":true,\"needs\":["
                            + "{\"id\":\"fact-a\",\"question\":\"公开事实 A 是什么？\","
                            + "\"eastmoneyFields\":[],\"webSearch\":true,\"sourceFocus\":[]},"
                            + "{\"id\":\"fact-b\",\"question\":\"公开事实 B 是什么？\","
                            + "\"eastmoneyFields\":[],\"webSearch\":true,\"sourceFocus\":[]}]}");
        }

        private void reviewSuccess() {
            when(reviewed.search(anyString(), anyString(), anyString(), anyLong()))
                    .thenAnswer(invocation -> acceptedPacket(invocation.getArgument(0),
                            invocation.getArgument(1), invocation.getArgument(2)));
        }

        private void reviewSuccessWith(String statement) {
            when(reviewed.search(anyString(), anyString(), anyString(), anyLong()))
                    .thenAnswer(invocation -> acceptedPacket(invocation.getArgument(0),
                            invocation.getArgument(1), invocation.getArgument(2), statement));
        }

        private MeetingReviewedSearchGap savedGap() {
            String asOf = LocalDate.now(MARKET_ZONE).toString();
            ArgumentCaptor<String> question = ArgumentCaptor.forClass(String.class);
            verify(reviewed, atLeastOnce()).search(anyString(), question.capture(), anyString(), anyLong());
            return ledger.findOrCreateGap(meetingId, 1, asOf, question.getAllValues().get(0));
        }
    }
}
