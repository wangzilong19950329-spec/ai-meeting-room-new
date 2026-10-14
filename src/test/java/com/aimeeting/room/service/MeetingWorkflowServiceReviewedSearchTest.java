package com.aimeeting.room.service;

import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.AiWorkflowStageTemplate;
import com.aimeeting.room.service.ai.WebSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MeetingWorkflowServiceReviewedSearchTest {
    @Test
    void rejectedN8nSearchBlocksRequiredStageWithoutUsingLegacyProviders() {
        MeetingWorkflowService service = mock(MeetingWorkflowService.class, CALLS_REAL_METHODS);
        MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        WebSearchService legacy = mock(WebSearchService.class);
        when(reviewed.isEnabled()).thenReturn(true);
        when(reviewed.search(anyString()))
                .thenThrow(new MeetingReviewedEvidenceService.EvidenceRejected("N8N_REVIEW_UNVERIFIED"));
        ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
        ReflectionTestUtils.setField(service, "webSearch", legacy);

        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("联合国宪章签署年份");
        AiWorkflowStageTemplate stage = new AiWorkflowStageTemplate();
        stage.setStageName("事实分析");
        stage.setStageType("ANALYSIS");
        stage.setSearchStrategy("REQUIRED");
        AiMeetingMember member = new AiMeetingMember();
        member.setMemberName("资料分析师");

        Object trace = ReflectionTestUtils.invokeMethod(service, "executeWorkflowStageSearch",
                room, null, stage, member);
        assertNotNull(trace);
        WebSearchService.SearchMeta meta = (WebSearchService.SearchMeta) ReflectionTestUtils.getField(trace, "meta");
        assertEquals("unverified", meta.status);
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(service,
                "isRequiredWorkflowSearchFailure", stage, trace));
        verifyNoInteractions(legacy);
    }
}
