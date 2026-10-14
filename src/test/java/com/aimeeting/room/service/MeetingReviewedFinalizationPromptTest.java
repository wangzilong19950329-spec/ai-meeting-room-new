package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dto.ConsensusResult;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MeetingReviewedFinalizationPromptTest {
    @Test
    void pendingVotedConclusionOverridesStillEmptyRoomFieldInBothPrompts() {
        AiMeetingRoomMapper rooms = mock(AiMeetingRoomMapper.class);
        AiMeetingMemberMapper members = mock(AiMeetingMemberMapper.class);
        AiMeetingOpinionMapper opinions = mock(AiMeetingOpinionMapper.class);
        AiMeetingRoom room = room();
        when(rooms.selectById(5L)).thenReturn(room);
        when(members.selectByMeetingId(5L)).thenReturn(List.of(member(11L, "价值派")));
        when(opinions.selectByMeetingId(5L)).thenReturn(Collections.emptyList());
        MeetingOrchestratorService orchestrator = new MeetingOrchestratorService();
        ReflectionTestUtils.setField(orchestrator, "roomMapper", rooms);
        ReflectionTestUtils.setField(orchestrator, "memberMapper", members);
        ReflectionTestUtils.setField(orchestrator, "opinionMapper", opinions);

        String summary = orchestrator.buildSummaryPrompt(5L, "两票支持，有条件通过");
        String artifact = orchestrator.buildDecisionArtifactPrompt(5L, "两票支持，有条件通过");
        assertTrue(summary.contains("【最终结论】两票支持，有条件通过"));
        assertTrue(artifact.contains("【最终结论】两票支持，有条件通过"));
        assertFalse(summary.contains("【最终结论】未达成"));
        assertFalse(artifact.contains("【最终结论】未达成"));
    }

    @Test
    void strictArtifactUsesServerConclusionAndNamesMissingAndSkippedSeats() {
        AiMeetingRoomMapper rooms = mock(AiMeetingRoomMapper.class);
        AiMeetingMemberMapper members = mock(AiMeetingMemberMapper.class);
        MeetingReviewedTurnSkipService skips = mock(MeetingReviewedTurnSkipService.class);
        when(rooms.selectById(5L)).thenReturn(room());
        when(members.selectByMeetingId(5L)).thenReturn(List.of(member(11L, "价值派"), member(12L, "技术派")));
        MeetingReviewedTurnSkip skip = new MeetingReviewedTurnSkip();
        skip.setMemberId(12L);
        skip.setReasonCode("N8N_REVIEW_UNVERIFIED");
        when(skips.listSkippedByMeetingAndRound(5L, 1)).thenReturn(List.of(skip));
        MeetingRoomService service = new MeetingRoomService();
        ReflectionTestUtils.setField(service, "roomMapper", rooms);
        ReflectionTestUtils.setField(service, "memberMapper", members);
        ReflectionTestUtils.setField(service, "reviewedTurnSkipService", skips);
        ConsensusResult result = ConsensusResult.partial("两票支持，有条件通过", null,
                List.of(11L), Collections.emptyList());
        result.setAbsentMemberIds(List.of(12L));

        String attendance = ReflectionTestUtils.invokeMethod(service,
                "buildReviewedFinalizationAttendance", 5L, 1, result);
        assertTrue(attendance.contains("未投票席位】技术派"));
        assertTrue(attendance.contains("技术派（N8N_REVIEW_UNVERIFIED）"));
        String modelArtifact = "{\"topicOverview\":\"主题\",\"projectKey\":null,\"projectContext\":null,"
                + "\"consensusPath\":\"过程\",\"finalDecision\":\"未达成\","
                + "\"keyViewpoints\":[],\"majorDisputes\":[],\"decisionBasis\":[],"
                + "\"openQuestions\":[],\"nextActions\":[]}";
        String normalized = ReflectionTestUtils.invokeMethod(service, "buildDecisionArtifactJsonStrict",
                5L, result.getConclusion(), "摘要", modelArtifact);
        assertEquals(result.getConclusion(), JSON.parseObject(normalized).getString("finalDecision"));
    }

    private static AiMeetingRoom room() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setId(5L);
        room.setTitle("主题");
        return room;
    }

    private static AiMeetingMember member(long id, String name) {
        AiMeetingMember member = new AiMeetingMember();
        member.setId(id);
        member.setMemberName(name);
        return member;
    }
}
