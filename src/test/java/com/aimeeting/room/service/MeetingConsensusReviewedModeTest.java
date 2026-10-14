package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingConclusionClusterMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingVoteMapper;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingVote;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingConsensusReviewedModeTest {
    @Test
    void votingUsesSelectedModelWithoutFallbackAndSkipsPersistedVotesOnResume() {
        MeetingConsensusService service = new MeetingConsensusService();
        AiMeetingVoteMapper votes = mock(AiMeetingVoteMapper.class);
        AiMeetingOpinionMapper opinions = mock(AiMeetingOpinionMapper.class);
        AiMeetingMemberMapper members = mock(AiMeetingMemberMapper.class);
        MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        MeetingReviewedConsensusWriter writer = mock(MeetingReviewedConsensusWriter.class);
        ReflectionTestUtils.setField(service, "voteMapper", votes);
        ReflectionTestUtils.setField(service, "opinionMapper", opinions);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
        ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
        ReflectionTestUtils.setField(service, "reviewedConsensusWriter", writer);
        when(reviewed.isEnabled()).thenReturn(true);
        AiMeetingMember member = new AiMeetingMember();
        member.setId(3L);
        member.setMemberName("技术分析师");
        member.setModelType("selected-model");
        when(members.selectByMeetingId(7L)).thenReturn(List.of(member));
        when(opinions.selectByMeetingIdAndRound(7L, 1)).thenReturn(List.of(reviewedStatement(3L)));
        when(votes.selectByMeetingIdAndRound(7L, 1)).thenReturn(Collections.emptyList());
        when(orchestrator.buildVotePrompt(7L, 3L)).thenReturn("vote prompt");
        when(orchestrator.callModelStrict(eq("selected-model"), anyString(), eq("vote prompt"), eq(null)))
                .thenReturn("{\"position\":\"支持\",\"confidence\":0.8,\"reasoning\":\"依据\"}");

        service.conductVoting(7L, 1, 2L);
        ArgumentCaptor<AiMeetingVote> saved = ArgumentCaptor.forClass(AiMeetingVote.class);
        verify(writer).insertVoteIfCurrent(saved.capture(), eq(2L));
        assertEquals("支持", saved.getValue().getVotePosition());
        verify(orchestrator, never()).callModel(anyString(), anyString(), anyString());
        verify(votes, never()).insert(any(AiMeetingVote.class));

        when(votes.selectByMeetingIdAndRound(7L, 1)).thenReturn(List.of(saved.getValue()));
        service.conductVoting(7L, 1, 2L);
        verify(writer).insertVoteIfCurrent(any(AiMeetingVote.class), eq(2L));
    }

    @Test
    void selectedModelFailureDoesNotInsertVote() {
        MeetingConsensusService service = new MeetingConsensusService();
        AiMeetingVoteMapper votes = mock(AiMeetingVoteMapper.class);
        AiMeetingOpinionMapper opinions = mock(AiMeetingOpinionMapper.class);
        AiMeetingMemberMapper members = mock(AiMeetingMemberMapper.class);
        MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        MeetingReviewedConsensusWriter writer = mock(MeetingReviewedConsensusWriter.class);
        ReflectionTestUtils.setField(service, "voteMapper", votes);
        ReflectionTestUtils.setField(service, "opinionMapper", opinions);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
        ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
        ReflectionTestUtils.setField(service, "reviewedConsensusWriter", writer);
        when(reviewed.isEnabled()).thenReturn(true);
        AiMeetingMember member = new AiMeetingMember();
        member.setId(3L);
        member.setMemberName("技术分析师");
        member.setModelType("selected-model");
        when(members.selectByMeetingId(7L)).thenReturn(List.of(member));
        when(opinions.selectByMeetingIdAndRound(7L, 1)).thenReturn(List.of(reviewedStatement(3L)));
        when(votes.selectByMeetingIdAndRound(7L, 1)).thenReturn(Collections.emptyList());
        when(orchestrator.buildVotePrompt(7L, 3L)).thenReturn("vote prompt");
        when(orchestrator.callModelStrict(eq("selected-model"), anyString(), eq("vote prompt"), eq(null)))
                .thenThrow(new IllegalStateException("meeting_selected_model_unavailable"));

        service.conductVoting(7L, 1, 2L);
        verify(votes, never()).insert(any());
        verify(writer, never()).insertVoteIfCurrent(any(), eq(2L));
        verify(orchestrator, never()).callModel(anyString(), anyString(), anyString());
    }

    @Test
    void reviewedCallsWithoutRunEpochFailClosed() {
        MeetingConsensusService service = new MeetingConsensusService();
        MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
        ReflectionTestUtils.setField(service, "memberMapper", mock(AiMeetingMemberMapper.class));
        when(reviewed.isEnabled()).thenReturn(true);
        assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> service.conductVoting(7L, 1));
        assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> service.clusterVotePositions(7L, 1));
    }

    @Test
    void reviewedClusterUsesEpochFencedWriterAfterModelCall() {
        MeetingConsensusService service = new MeetingConsensusService();
        MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        MeetingReviewedConsensusWriter writer = mock(MeetingReviewedConsensusWriter.class);
        AiMeetingConclusionClusterMapper clusters = mock(AiMeetingConclusionClusterMapper.class);
        AiMeetingVoteMapper votes = mock(AiMeetingVoteMapper.class);
        AiMeetingOpinionMapper opinions = mock(AiMeetingOpinionMapper.class);
        AiMeetingMemberMapper members = mock(AiMeetingMemberMapper.class);
        MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        com.aimeeting.room.config.AiModelProperties properties = mock(com.aimeeting.room.config.AiModelProperties.class);
        com.aimeeting.room.config.AiModelProperties.MeetingConfig meetingConfig =
                mock(com.aimeeting.room.config.AiModelProperties.MeetingConfig.class);
        ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
        ReflectionTestUtils.setField(service, "reviewedConsensusWriter", writer);
        ReflectionTestUtils.setField(service, "clusterMapper", clusters);
        ReflectionTestUtils.setField(service, "voteMapper", votes);
        ReflectionTestUtils.setField(service, "opinionMapper", opinions);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
        ReflectionTestUtils.setField(service, "aiModelProperties", properties);
        when(reviewed.isEnabled()).thenReturn(true);
        when(clusters.selectByMeetingIdAndRound(7L, 1)).thenReturn(Collections.emptyList());
        AiMeetingMember first = new AiMeetingMember();
        first.setId(3L);
        AiMeetingMember second = new AiMeetingMember();
        second.setId(4L);
        when(members.selectByMeetingId(7L)).thenReturn(List.of(first, second));
        when(opinions.selectByMeetingIdAndRound(7L, 1)).thenReturn(List.of(
                reviewedStatement(3L), reviewedStatement(4L)));
        when(votes.selectByMeetingIdAndRound(7L, 1)).thenReturn(List.of(vote(3L), vote(4L)));
        when(properties.getMeeting()).thenReturn(meetingConfig);
        when(meetingConfig.getConsensusThreshold()).thenReturn(0.7);
        when(meetingConfig.getMinAgreementCount()).thenReturn(2);
        when(orchestrator.resolveMeetingModelType(7L)).thenReturn("selected-model");
        when(orchestrator.buildClusterPrompt(7L, 1)).thenReturn("cluster prompt");
        when(orchestrator.callModelStrict(eq("selected-model"), anyString(), eq("cluster prompt"), eq(800)))
                .thenReturn("[{\"clusterLabel\":\"支持\",\"memberIds\":[3,4]}]");

        service.clusterVotePositions(7L, 1, 2L);
        verify(writer).insertClustersIfCurrent(eq(7L), eq(1), any(), eq(2L));
        verify(clusters, never()).batchInsert(any());

        when(writer.insertClustersIfCurrent(eq(7L), eq(1), any(), eq(2L)))
                .thenThrow(new MeetingReviewedEvidenceService.EvidenceRejected("MEETING_RUN_STALE"));
        assertThrows(MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> service.clusterVotePositions(7L, 1, 2L));
    }

    private static AiMeetingOpinion reviewedStatement(long memberId) {
        AiMeetingOpinion opinion = new AiMeetingOpinion();
        opinion.setMemberId(memberId);
        opinion.setOpinionType(0);
        opinion.setEvidence("{\"reviewedSearchStatus\":\"CROSS_CHECKED\"}");
        return opinion;
    }

    private static AiMeetingVote vote(long memberId) {
        AiMeetingVote vote = new AiMeetingVote();
        vote.setMemberId(memberId);
        return vote;
    }
}
