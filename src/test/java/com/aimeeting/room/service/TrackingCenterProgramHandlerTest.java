package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingTrackingItemMapper;
import com.aimeeting.room.dao.AiMeetingTrackingUpdateMapper;
import com.aimeeting.room.entity.AiMeetingTrackingItem;
import com.aimeeting.room.service.ai.WebSearchService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Items judged by the program (such as an outcome at expiry) are refreshed by their handler, never by web search. */
class TrackingCenterProgramHandlerTest {

    private final AiMeetingTrackingItemMapper items = mock(AiMeetingTrackingItemMapper.class);
    private final AiMeetingTrackingUpdateMapper updates = mock(AiMeetingTrackingUpdateMapper.class);
    private final WebSearchService search = mock(WebSearchService.class);
    private final ProgramTrackingHandler handler = mock(ProgramTrackingHandler.class);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final TrackingCenterService center = new TrackingCenterService(items, updates,
            mock(AiMeetingRoomMapper.class), search, mock(MeetingRoomService.class), executor);
    private final AiMeetingTrackingItem item = new AiMeetingTrackingItem();

    TrackingCenterProgramHandlerTest() {
        item.setId(11L);
        item.setMeetingId(5L);
        item.setItemType("OUTCOME_EXPIRY");
        item.setTitle("结果到期判定：未来60个交易日收益率");
        item.setStatus("OPEN");
        when(items.selectById(11L)).thenReturn(item);
        when(handler.supports("OUTCOME_EXPIRY")).thenReturn(true);
        center.setProgramHandlers(List.of(handler));
    }

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    @Test
    void judgedItemStopsCheckingAndWaitsForReview() {
        when(handler.refresh(item)).thenReturn(new ProgramTrackingHandler.Check("success", "到期判定：实际 +10.00%", null, true));

        center.refreshItemNow(11L);

        verify(search, never()).searchRecent(anyString(), anyInt(), any(), any());
        verify(items).updateRefreshState(eq(11L), isNull(), any(Date.class), eq("success"),
                eq("到期判定：实际 +10.00%"), any(), eq("NONE"), any(Date.class));
        verify(items).updateStatus(eq(11L), eq("READY_FOR_REVIEW"), any(Date.class), isNull());
        verify(updates).insert(any());
    }

    @Test
    void itemNotDueYetKeepsItsStatusAndIsCheckedAgain() {
        Date tomorrow = new Date(System.currentTimeMillis() + 86_400_000L);
        when(handler.refresh(item)).thenReturn(new ProgramTrackingHandler.Check("pending", "未到期：已过 10/60 个交易日", tomorrow, false));

        center.refreshItemNow(11L);

        verify(search, never()).searchRecent(anyString(), anyInt(), any(), any());
        verify(items).updateRefreshState(eq(11L), eq(tomorrow), any(Date.class), eq("pending"),
                eq("未到期：已过 10/60 个交易日"), any(), eq("NONE"), any(Date.class));
        verify(items, never()).updateStatus(any(), any(), any(), any());
    }
}
