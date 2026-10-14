package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.dao.AiMeetingConclusionClusterMapper;
import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingVoteMapper;
import com.aimeeting.room.dto.ConsensusResult;
import com.aimeeting.room.entity.AiMeetingConclusionCluster;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.AiMeetingVote;
import com.aimeeting.room.service.ai.WebSearchService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingConsensusReviewedAttendanceTest {
    private static final long MEETING_ID = 7L;
    private static final int ROUND = 1;
    private static final long EPOCH = 2L;

    @Test
    void absentOrUnreviewedMemberNeverReceivesABallot() {
        Fixture f = new Fixture();
        f.members(1L, 2L, 3L);
        f.opinions(statement(1L, "CROSS_CHECKED"), statement(2L, null));
        when(f.orchestrator.buildVotePrompt(MEETING_ID, 1L)).thenReturn("prompt");
        when(f.orchestrator.callModelStrict(eq("selected-model"), anyString(), eq("prompt"), eq(null)))
                .thenReturn("{\"position\":\"支持\",\"confidence\":0.8,\"reasoning\":\"依据\"}");

        assertEquals(Set.of(1L), f.service.reviewedEligibleSpeakerIds(MEETING_ID, ROUND));
        f.service.conductVoting(MEETING_ID, ROUND, EPOCH);

        ArgumentCaptor<AiMeetingVote> saved = ArgumentCaptor.forClass(AiMeetingVote.class);
        verify(f.writer).insertVoteIfCurrent(saved.capture(), eq(EPOCH));
        assertEquals(1L, saved.getValue().getMemberId());
        verify(f.orchestrator, never()).buildVotePrompt(MEETING_ID, 2L);
        verify(f.orchestrator, never()).buildVotePrompt(MEETING_ID, 3L);
    }

    @Test
    void aFailedVoteModelDoesNotFabricateABallotOrStopOtherVoters() {
        Fixture f = new Fixture();
        f.members(1L, 2L);
        f.opinions(statement(1L, "STRUCTURED_ONLY"), statement(2L, "NO_SEARCH_NEEDED"));
        when(f.orchestrator.buildVotePrompt(MEETING_ID, 1L)).thenReturn("first");
        when(f.orchestrator.buildVotePrompt(MEETING_ID, 2L)).thenReturn("second");
        when(f.orchestrator.callModelStrict(eq("selected-model"), anyString(), eq("first"), eq(null)))
                .thenThrow(new IllegalStateException("model unavailable"));
        when(f.orchestrator.callModelStrict(eq("selected-model"), anyString(), eq("second"), eq(null)))
                .thenReturn("{\"position\":\"谨慎\",\"confidence\":0.7,\"reasoning\":\"依据\"}");

        f.service.conductVoting(MEETING_ID, ROUND, EPOCH);

        ArgumentCaptor<AiMeetingVote> saved = ArgumentCaptor.forClass(AiMeetingVote.class);
        verify(f.writer).insertVoteIfCurrent(saved.capture(), eq(EPOCH));
        assertEquals(2L, saved.getValue().getMemberId());
    }

    @Test
    void lowConfidenceSpeakerCanVoteWithEvidenceCaveat() {
        Fixture f = new Fixture();
        f.members(1L, 2L);
        AiMeetingOpinion lowConfidence = statement(1L, "LOW_CONFIDENCE");
        lowConfidence.setEvidence(limitedEvidenceWithAcceptedClaim(false).toJSONString());
        f.opinions(lowConfidence, statement(2L, "CROSS_CHECKED"));
        when(f.orchestrator.buildVotePrompt(MEETING_ID, 1L)).thenReturn("first");
        when(f.orchestrator.buildVotePrompt(MEETING_ID, 2L)).thenReturn("second");
        when(f.orchestrator.callModelStrict(eq("selected-model"), anyString(), anyString(), eq(null)))
                .thenReturn("{\"position\":\"谨慎\",\"confidence\":0.6,\"reasoning\":\"有条件\"}");

        assertEquals(Set.of(1L, 2L), f.service.reviewedEligibleSpeakerIds(MEETING_ID, ROUND));
        f.service.conductVoting(MEETING_ID, ROUND, EPOCH);

        ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
        verify(f.orchestrator, org.mockito.Mockito.times(2)).callModelStrict(
                eq("selected-model"), prompts.capture(), anyString(), eq(null));
        assertEquals(2, prompts.getAllValues().stream()
                .filter(prompt -> prompt.contains("待核验数字")).count());
        verify(f.writer, org.mockito.Mockito.times(2)).insertVoteIfCurrent(any(), eq(EPOCH));
    }

    @Test
    void speakerWithUnsearchedBudgetGapsCannotCountAsReviewedVoter() {
        Fixture f = new Fixture();
        f.members(1L, 2L);
        AiMeetingOpinion budgetLimited = statement(1L, "LOW_CONFIDENCE");
        budgetLimited.setEvidence("{\"reviewedSearchStatus\":\"LOW_CONFIDENCE\","
                + "\"reviewedSearchCoverageStatus\":\"UNSEARCHED_BUDGET\","
                + "\"reviewedSearchDecision\":{\"unsearchedGapCount\":1}}");
        f.opinions(budgetLimited, statement(2L, "CROSS_CHECKED"));
        when(f.orchestrator.buildVotePrompt(MEETING_ID, 2L)).thenReturn("second");
        when(f.orchestrator.callModelStrict(eq("selected-model"), anyString(), eq("second"), eq(null)))
                .thenReturn("{\"position\":\"谨慎\",\"confidence\":0.6,\"reasoning\":\"有条件\"}");

        assertEquals(Set.of(2L), f.service.reviewedEligibleSpeakerIds(MEETING_ID, ROUND));
        f.service.conductVoting(MEETING_ID, ROUND, EPOCH);
        verify(f.orchestrator, never()).buildVotePrompt(MEETING_ID, 1L);
        verify(f.writer).insertVoteIfCurrent(any(), eq(EPOCH));
    }

    @Test
    void speakerWithAnUnverifiedGapCannotCountAsReviewedVoter() {
        Fixture f = new Fixture();
        f.members(1L, 2L);
        AiMeetingOpinion incomplete = statement(1L, "LOW_CONFIDENCE");
        incomplete.setEvidence("{\"reviewedSearchStatus\":\"LOW_CONFIDENCE\","
                + "\"reviewedSearchCoverageStatus\":\"UNVERIFIED_GAP\","
                + "\"reviewedSearchDecision\":{\"unverifiedGapCount\":1}}");
        f.opinions(incomplete, statement(2L, "CROSS_CHECKED"));

        assertEquals(Set.of(2L), f.service.reviewedEligibleSpeakerIds(MEETING_ID, ROUND));
    }

    @Test
    void provisionalOnlySpeakerCannotVoteEvenWithoutCoverageMarker() {
        Fixture f = new Fixture();
        f.members(1L, 2L);
        AiMeetingOpinion provisional = statement(1L, "LOW_CONFIDENCE");
        provisional.setEvidence("{\"reviewedSearchStatus\":\"LOW_CONFIDENCE\","
                + "\"results\":[{\"provider\":\"n8n-deepseek-provisional\","
                + "\"sourceType\":\"LOW_CONFIDENCE_SEARCH\",\"rawHash\":\"candidate\"}]}");
        f.opinions(provisional, statement(2L, "CROSS_CHECKED"));

        assertEquals(Set.of(2L), f.service.reviewedEligibleSpeakerIds(MEETING_ID, ROUND));
    }

    @Test
    void speakerWithAnUnverifiedGapCanVoteOnAnotherAcceptedFact() {
        Fixture f = new Fixture();
        f.members(1L, 2L, 3L);
        AiMeetingOpinion limited = statement(1L, "LOW_CONFIDENCE");
        limited.setEvidence(limitedEvidenceWithAcceptedClaim(false).toJSONString());
        f.opinions(limited, statement(2L, "CROSS_CHECKED"));

        assertEquals(Set.of(1L, 2L), f.service.reviewedEligibleSpeakerIds(MEETING_ID, ROUND));

        JSONObject contradicted = limitedEvidenceWithAcceptedClaim(true);
        limited.setEvidence(contradicted.toJSONString());
        assertEquals(Set.of(2L), f.service.reviewedEligibleSpeakerIds(MEETING_ID, ROUND));
    }

    @Test
    void speakerWithUnsearchedGapCanVoteOnlyWhenEastmoneyDeliveredStructuredFact() {
        Fixture f = new Fixture();
        f.members(1L, 2L);
        AiMeetingOpinion limited = statement(1L, "LOW_CONFIDENCE");
        JSONObject evidence = JSON.parseObject("{\"reviewedSearchStatus\":\"LOW_CONFIDENCE\","
                + "\"reviewedSearchCoverageStatus\":\"UNSEARCHED_BUDGET\","
                + "\"results\":[{\"provider\":\"eastmoney\",\"sourceType\":\"STRUCTURED_DATA\","
                + "\"query\":\"EASTMONEY:300750.sz:quote\","
                + "\"url\":\"https://quote.eastmoney.com/sz300750.html\",\"rawHash\":\"sha256:test\"}]}");
        limited.setEvidence(evidence.toJSONString());
        f.opinions(limited, statement(2L, "CROSS_CHECKED"));

        assertEquals(Set.of(1L, 2L), f.service.reviewedEligibleSpeakerIds(MEETING_ID, ROUND));
        evidence.getJSONArray("results").getJSONObject(0).put("sourceType", "LOW_CONFIDENCE_SEARCH");
        limited.setEvidence(evidence.toJSONString());
        assertEquals(Set.of(2L), f.service.reviewedEligibleSpeakerIds(MEETING_ID, ROUND));
    }

    @Test
    void clusterCannotInventDuplicateOrOmitVoters() {
        Fixture f = new Fixture();
        f.members(1L, 2L, 3L);
        f.opinions(statement(1L, "CROSS_CHECKED"), statement(2L, "CROSS_CHECKED"));
        when(f.votes.selectByMeetingIdAndRound(MEETING_ID, ROUND)).thenReturn(List.of(vote(1L), vote(2L)));
        when(f.orchestrator.resolveMeetingModelType(MEETING_ID)).thenReturn("selected-model");
        when(f.orchestrator.buildClusterPrompt(MEETING_ID, ROUND)).thenReturn("cluster prompt");
        when(f.orchestrator.callModelStrict(eq("selected-model"), anyString(), eq("cluster prompt"), eq(800)))
                .thenReturn("[{\"clusterLabel\":\"支持\",\"memberIds\":[1,2,3]}]")
                .thenReturn("[{\"clusterLabel\":\"支持\",\"memberIds\":[1]},{\"clusterLabel\":\"反对\",\"memberIds\":[1,2]}]")
                .thenReturn("[{\"clusterLabel\":\"支持\",\"memberIds\":[1]}]");

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThrows(IllegalStateException.class,
                    () -> f.service.clusterVotePositions(MEETING_ID, ROUND, EPOCH));
        }
        verify(f.writer, never()).insertClustersIfCurrent(eq(MEETING_ID), eq(ROUND), any(), eq(EPOCH));
    }

    @Test
    void clusterRecomputesCoverageAgainstOriginalMemberCount() {
        Fixture f = new Fixture();
        f.members(1L, 2L, 3L);
        f.opinions(statement(1L, "CROSS_CHECKED"), statement(2L, "CROSS_CHECKED"));
        when(f.votes.selectByMeetingIdAndRound(MEETING_ID, ROUND)).thenReturn(List.of(vote(1L), vote(2L)));
        when(f.orchestrator.resolveMeetingModelType(MEETING_ID)).thenReturn("selected-model");
        when(f.orchestrator.buildClusterPrompt(MEETING_ID, ROUND)).thenReturn("cluster prompt");
        when(f.orchestrator.callModelStrict(eq("selected-model"), anyString(), eq("cluster prompt"), eq(800)))
                .thenReturn("[{\"clusterLabel\":\"支持\",\"memberIds\":[1,2]}]");

        f.service.clusterVotePositions(MEETING_ID, ROUND, EPOCH);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiMeetingConclusionCluster>> saved = ArgumentCaptor.forClass(List.class);
        verify(f.writer).insertClustersIfCurrent(eq(MEETING_ID), eq(ROUND), saved.capture(), eq(EPOCH));
        AiMeetingConclusionCluster cluster = saved.getValue().get(0);
        assertEquals(2, cluster.getMemberCount());
        assertEquals(0, cluster.getAgreementRatio().compareTo(new BigDecimal("0.67")));
        assertEquals(0, cluster.getIsConsensus());
    }

    @Test
    void absentMemberIsNeitherAgreementNorDissentInForcedResult() {
        Fixture f = new Fixture();
        f.members(1L, 2L, 3L, 4L);
        f.opinions(statement(1L, "CROSS_CHECKED"), statement(2L, "CROSS_CHECKED"),
                statement(3L, "STRUCTURED_ONLY"));
        when(f.votes.selectByMeetingIdAndRound(MEETING_ID, ROUND)).thenReturn(
                List.of(vote(1L), vote(2L), vote(3L)));
        AiMeetingRoom room = new AiMeetingRoom();
        room.setCurrentRound(ROUND);
        when(f.rooms.selectById(MEETING_ID)).thenReturn(room);
        when(f.clusters.selectByMeetingIdAndRound(MEETING_ID, ROUND)).thenReturn(List.of(
                cluster("支持", "1,2", 2, "0.50", 0),
                cluster("反对", "3", 1, "0.25", 0)));

        ConsensusResult result = f.service.forceConclude(MEETING_ID);

        assertFalse(result.isConsensusReached());
        assertEquals(List.of(1L, 2L), result.getAgreeingMemberIds());
        assertEquals(List.of(3L), result.getDissentingMemberIds());
        assertEquals(List.of(4L), result.getAbsentMemberIds());
    }

    @Test
    void forcedResultDoesNotCallEitherSideDominantWhenRemainingVotersTie() {
        Fixture f = new Fixture();
        f.members(1L, 2L, 3L);
        f.opinions(statement(1L, "CROSS_CHECKED"), statement(2L, "CROSS_CHECKED"));
        when(f.votes.selectByMeetingIdAndRound(MEETING_ID, ROUND)).thenReturn(List.of(vote(1L), vote(2L)));
        AiMeetingRoom room = new AiMeetingRoom();
        room.setCurrentRound(ROUND);
        when(f.rooms.selectById(MEETING_ID)).thenReturn(room);
        when(f.clusters.selectByMeetingIdAndRound(MEETING_ID, ROUND)).thenReturn(List.of(
                cluster("支持", "1", 1, "0.33", 0),
                cluster("反对", "2", 1, "0.33", 0)));

        ConsensusResult result = f.service.forceConclude(MEETING_ID);

        assertFalse(result.isConsensusReached());
        assertEquals(true, result.getConclusion().contains("并列"));
        assertEquals(List.of(), result.getAgreeingMemberIds());
        assertEquals(List.of(), result.getDissentingMemberIds());
        assertEquals(List.of(3L), result.getAbsentMemberIds());
    }

    @Test
    void resumedConsensusRejectsAPersistedClusterThatCountsAnAbsentMember() {
        Fixture f = new Fixture();
        f.members(1L, 2L, 3L);
        f.opinions(statement(1L, "CROSS_CHECKED"), statement(2L, "CROSS_CHECKED"));
        when(f.votes.selectByMeetingIdAndRound(MEETING_ID, ROUND)).thenReturn(List.of(vote(1L), vote(2L)));
        when(f.clusters.selectByMeetingIdAndRound(MEETING_ID, ROUND)).thenReturn(List.of(
                cluster("支持", "1,2,3", 3, "1.00", 1)));

        assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> f.service.checkConsensus(MEETING_ID, ROUND));
    }

    @Test
    void consensusCanReachOriginalQuorumWithoutCallingAbsentMemberADissenter() {
        Fixture f = new Fixture();
        f.members(1L, 2L, 3L, 4L);
        f.opinions(statement(1L, "CROSS_CHECKED"), statement(2L, "CROSS_CHECKED"),
                statement(3L, "STRUCTURED_ONLY"));
        when(f.votes.selectByMeetingIdAndRound(MEETING_ID, ROUND)).thenReturn(
                List.of(vote(1L), vote(2L), vote(3L)));
        when(f.clusters.selectByMeetingIdAndRound(MEETING_ID, ROUND)).thenReturn(List.of(
                cluster("支持", "1,2,3", 3, "0.75", 1)));

        ConsensusResult result = f.service.checkConsensus(MEETING_ID, ROUND);

        assertEquals(true, result.isConsensusReached());
        assertEquals(List.of(1L, 2L, 3L), result.getAgreeingMemberIds());
        assertEquals(List.of(), result.getDissentingMemberIds());
        assertEquals(List.of(4L), result.getAbsentMemberIds());
    }

    private static AiMeetingMember member(long id) {
        AiMeetingMember member = new AiMeetingMember();
        member.setId(id);
        member.setMemberName("议员" + id);
        member.setModelType("selected-model");
        return member;
    }

    private static AiMeetingOpinion statement(long memberId, String status) {
        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMemberId(memberId);
        opinion.setOpinionType(0);
        opinion.setEvidence(status == null ? "{}" : "{\"reviewedSearchStatus\":\"" + status + "\"}");
        return opinion;
    }

    private static JSONObject limitedEvidenceWithAcceptedClaim(boolean contradicted) {
        JSONObject packet = JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                + "\"requestId\":\"meeting-accepted\",\"question\":\"公开事实是什么？\","
                + "\"asOf\":\"2026-10-06\",\"status\":\"PARTIAL\","
                + "\"audit\":{\"searchCalls\":1,\"reviewCalls\":1,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"FOUND\",\"searchCount\":1,"
                + "\"ids\":[\"fact\"],\"links\":[\"https://example.org/a\"]},"
                + "{\"phase\":\"review\",\"id\":\"fact\",\"verdict\":\"PASS\","
                + "\"reviewerVerdict\":\"PASS\",\"searchCount\":1,"
                + "\"links\":[\"https://example.org/b\"]}]},"
                + "\"claims\":[{\"id\":\"fact\",\"statement\":\"经核验的事实\","
                + "\"sourceUrls\":[\"https://example.org/a\"],"
                + "\"reviewLinks\":[\"https://example.org/b\"],"
                + "\"reviewMethod\":\"TWO_ISOLATED_DEEPSEEK_SEARCHES\"}]} ");
        WebSearchService.SearchResult verified = MeetingReviewedEvidenceService.acceptPacket(
                packet, "meeting-accepted", "公开事实是什么？")
                .results.get(0);
        if (contradicted) packet.getJSONObject("audit").getJSONArray("trail")
                .add(JSON.parseObject("{\"phase\":\"review\",\"id\":\"fact\",\"verdict\":\"REJECT\"}"));
        JSONObject result = new JSONObject();
        result.put("provider", verified.provider);
        result.put("sourceType", verified.sourceType);
        result.put("url", verified.url);
        result.put("query", verified.query);
        result.put("rawHash", verified.rawHash);
        JSONObject evidence = new JSONObject();
        evidence.put("reviewedSearchStatus", "LOW_CONFIDENCE");
        evidence.put("reviewedSearchCoverageStatus", "UNVERIFIED_GAP");
        evidence.put("reviewedSearchAsOf", "2026-10-06");
        JSONArray packets = new JSONArray();
        packets.add(packet);
        evidence.put("reviewedSearchPackets", packets);
        JSONArray results = new JSONArray();
        results.add(result);
        evidence.put("results", results);
        return evidence;
    }

    private static AiMeetingVote vote(long memberId) {
        AiMeetingVote vote = new AiMeetingVote();
        vote.setMemberId(memberId);
        return vote;
    }

    private static AiMeetingConclusionCluster cluster(String label, String ids, int count,
                                                      String ratio, int consensus) {
        AiMeetingConclusionCluster cluster = new AiMeetingConclusionCluster();
        cluster.setClusterLabel(label);
        cluster.setMemberIds(ids);
        cluster.setMemberCount(count);
        cluster.setAgreementRatio(new BigDecimal(ratio));
        cluster.setIsConsensus(consensus);
        return cluster;
    }

    private static final class Fixture {
        private final MeetingConsensusService service = new MeetingConsensusService();
        private final AiMeetingVoteMapper votes = mock(AiMeetingVoteMapper.class);
        private final AiMeetingOpinionMapper opinions = mock(AiMeetingOpinionMapper.class);
        private final AiMeetingMemberMapper members = mock(AiMeetingMemberMapper.class);
        private final AiMeetingRoomMapper rooms = mock(AiMeetingRoomMapper.class);
        private final AiMeetingConclusionClusterMapper clusters = mock(AiMeetingConclusionClusterMapper.class);
        private final MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        private final MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        private final MeetingReviewedConsensusWriter writer = mock(MeetingReviewedConsensusWriter.class);

        private Fixture() {
            ReflectionTestUtils.setField(service, "voteMapper", votes);
            ReflectionTestUtils.setField(service, "opinionMapper", opinions);
            ReflectionTestUtils.setField(service, "memberMapper", members);
            ReflectionTestUtils.setField(service, "roomMapper", rooms);
            ReflectionTestUtils.setField(service, "clusterMapper", clusters);
            ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
            ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
            ReflectionTestUtils.setField(service, "reviewedConsensusWriter", writer);
            AiModelProperties properties = new AiModelProperties();
            properties.getMeeting().setConsensusThreshold(0.70);
            properties.getMeeting().setMinAgreementCount(2);
            ReflectionTestUtils.setField(service, "aiModelProperties", properties);
            when(reviewed.isEnabled()).thenReturn(true);
        }

        private void members(Long... ids) {
            when(members.selectByMeetingId(MEETING_ID)).thenReturn(
                    java.util.Arrays.stream(ids).map(MeetingConsensusReviewedAttendanceTest::member)
                            .collect(java.util.stream.Collectors.toList()));
        }

        private void opinions(AiMeetingOpinion... entries) {
            when(opinions.selectByMeetingIdAndRound(MEETING_ID, ROUND)).thenReturn(List.of(entries));
        }
    }
}
