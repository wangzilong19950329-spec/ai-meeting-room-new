package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.AiMeetingTrackingItemMapper;
import com.aimeeting.room.dao.AiMeetingTrackingUpdateMapper;
import com.aimeeting.room.entity.AiMeetingTrackingItem;
import com.aimeeting.room.service.ai.WebSearchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrackingCenterRecentRefreshTest {

    @Test
    void emptyRecentWindowDoesNotBecomeMaterialChange() {
        AiMeetingTrackingItemMapper items = mock(AiMeetingTrackingItemMapper.class);
        AiMeetingTrackingUpdateMapper updates = mock(AiMeetingTrackingUpdateMapper.class);
        AiMeetingRoomMapper rooms = mock(AiMeetingRoomMapper.class);
        WebSearchService search = mock(WebSearchService.class);
        MeetingRoomService meeting = mock(MeetingRoomService.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AiMeetingTrackingItem item = new AiMeetingTrackingItem();
            item.setId(7L);
            item.setMeetingId(3L);
            item.setTitle("量化因子评估");
            item.setWatchQuery("量化因子评估");
            item.setStatus("OPEN");
            item.setReopenPolicy("AUTO");
            item.setLastCheckFingerprint("previous-fingerprint");
            item.setLastCheckNote("此前有结果");
            when(items.selectById(7L)).thenReturn(item);
            when(updates.selectByTrackingItemId(7L)).thenReturn(Collections.emptyList());
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            when(search.searchRecent(eq("量化因子评估"), eq(4), eq(today.minusMonths(3)), eq(today)))
                    .thenReturn(Collections.emptyList());
            WebSearchService.SearchMeta meta = new WebSearchService.SearchMeta();
            meta.provider = "tavily";
            when(search.consumeSessionMeta()).thenReturn(meta);

            new TrackingCenterService(items, updates, rooms, search, meeting, executor).refreshItemNow(7L);

            ArgumentCaptor<String> note = ArgumentCaptor.forClass(String.class);
            verify(items).updateRefreshState(eq(7L), any(), any(Date.class), eq("no_result"),
                    note.capture(), eq("previous-fingerprint"), eq("NONE"), any(Date.class));
            assertTrue(note.getValue().contains("近三个月"));
            verify(search, never()).buildResearchDigest(any(), any(), any(), any(), any());
            verify(meeting, never()).reopenMeetingFromTrackingSignal(any(), any(), any(), any(), any(), any());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void firstResultAfterEmptyWindowDoesNotCompareAgainstEmptyStatusNote() {
        AiMeetingTrackingItemMapper items = mock(AiMeetingTrackingItemMapper.class);
        AiMeetingTrackingUpdateMapper updates = mock(AiMeetingTrackingUpdateMapper.class);
        AiMeetingRoomMapper rooms = mock(AiMeetingRoomMapper.class);
        WebSearchService search = mock(WebSearchService.class);
        MeetingRoomService meeting = mock(MeetingRoomService.class);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            AiMeetingTrackingItem item = new AiMeetingTrackingItem();
            item.setId(8L);
            item.setMeetingId(3L);
            item.setTitle("量化因子评估");
            item.setStatus("OPEN");
            item.setReopenPolicy("AUTO");
            item.setLastCheckStatus("no_result");
            item.setLastCheckFingerprint("older-fingerprint");
            item.setLastCheckNote("近三个月内未检索到新结果");
            when(items.selectById(8L)).thenReturn(item);
            when(updates.selectByTrackingItemId(8L)).thenReturn(Collections.emptyList());
            WebSearchService.SearchResult result = new WebSearchService.SearchResult();
            result.source = "recent";
            result.snippet = "发布日期：2026-10-04；新数据";
            result.url = "https://example.com/recent";
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            when(search.searchRecent(eq("量化因子评估"), eq(4), eq(today.minusMonths(3)), eq(today)))
                    .thenReturn(List.of(result));

            new TrackingCenterService(items, updates, rooms, search, meeting, executor).refreshItemNow(8L);

            verify(items).updateRefreshState(eq(8L), any(), any(Date.class), eq("success"),
                    any(), any(), eq("MINOR"), any(Date.class));
            verify(meeting, never()).reopenMeetingFromTrackingSignal(any(), any(), any(), any(), any(), any());
        } finally {
            executor.shutdownNow();
        }
    }
}
