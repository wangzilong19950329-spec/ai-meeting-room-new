package com.aimeeting.room.service;

import com.aimeeting.room.dto.ProgramTrackingItemSpec;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.AiMeetingTrackingItem;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProgramTrackingItemBuildTest {

    @Test
    void outcomeExpiryItemIsCheckedAutomaticallyAndNeverBlocksContinuing() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setId(4L);
        room.setCreatorId(1L);
        room.setTitle("宁德时代未来三个月价格走势");
        Date due = new Date(1_800_000_000_000L);
        Date now = new Date(1_790_000_000_000L);
        ProgramTrackingItemSpec spec = new ProgramTrackingItemSpec();
        spec.setItemType("OUTCOME_EXPIRY");
        spec.setTitle("结果到期判定：未来60个交易日收益率");
        spec.setDescription("到期后由程序按前复权日K判定并给议员打分");
        spec.setOwnerType("SYSTEM");
        spec.setAutoRefresh(true);
        spec.setNextCheckAt(due);
        spec.setDueAt(due);

        AiMeetingTrackingItem item = MeetingWorkflowService.buildProgramTrackingItem(room, 7L, 8L, spec, now);

        assertEquals(4L, item.getMeetingId());
        assertEquals(7L, item.getWorkflowRunId());
        assertEquals(8L, item.getStageRunId());
        assertEquals("OUTCOME_EXPIRY", item.getItemType());
        assertEquals("OPEN", item.getStatus());
        assertEquals("SYSTEM", item.getOwnerType());
        assertEquals(1, item.getAutoRefreshEnabled());
        assertEquals(0, item.getRequiredForContinue());
        assertEquals(0, item.getReopenTrigger());
        assertEquals(due, item.getNextCheckAt());
        assertEquals(due, item.getDueAt());
    }

    @Test
    void manualItemIsNeverRefreshedAutomatically() {
        AiMeetingRoom room = new AiMeetingRoom();
        room.setId(4L);
        ProgramTrackingItemSpec spec = new ProgramTrackingItemSpec();
        spec.setItemType("ASSUMPTION_DISPUTE");
        spec.setTitle("假设分歧：订单与需求");
        spec.setOwnerType("HUMAN");

        AiMeetingTrackingItem item = MeetingWorkflowService.buildProgramTrackingItem(room, 7L, null, spec, new Date());

        assertEquals(0, item.getAutoRefreshEnabled());
        assertNull(item.getNextCheckAt());
        assertEquals("HUMAN", item.getOwnerType());
    }
}
