package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchItemMapper;
import com.aimeeting.room.entity.StockWatchItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockWatchAnalysisPolicyServiceTest {

    @Mock private StockWatchItemMapper itemMapper;
    private StockWatchAnalysisPolicyService service;

    @BeforeEach
    void setUp() {
        service = new StockWatchAnalysisPolicyService(itemMapper, new StockWatchAnalysisSchedulePlanner());
    }

    @Test
    void defaultsPreserveLegacyThirtyMinuteBehavior() {
        StockWatchItem item = new StockWatchItem();
        service.applyDefaults(item);

        assertEquals("INTERVAL", item.getAnalysisMode());
        assertEquals(30, item.getAnalysisIntervalMinutes());
        assertEquals("IMMEDIATE_ANALYSIS", item.getAnomalyTriggerMode());
        assertEquals(0L, item.getAnalysisPolicyVersion());
    }

    @Test
    void pausedForcesAlertOnlyAndDisablesExtraReviews() {
        StockWatchItem item = item();
        item.setAnalysisMode("PAUSED");
        item.setAnomalyTriggerMode("IMMEDIATE_ANALYSIS");
        item.setWeeklyReviewEnabled(true);
        item.setMonthlyReviewEnabled(true);

        service.applyDefaults(item);

        assertEquals("ALERT_ONLY", item.getAnomalyTriggerMode());
        assertFalse(item.getWeeklyReviewEnabled());
        assertFalse(item.getMonthlyReviewEnabled());
        assertNull(item.getAnalysisIntervalMinutes());
    }

    @Test
    void intervalValidationRejectsOutOfRangeValue() {
        StockWatchItem current = item();
        when(itemMapper.selectBySymbolMarket("600000", "sh")).thenReturn(current);
        Map<String, Object> body = policy("INTERVAL", 9, 0L);

        assertThrows(IllegalArgumentException.class, () -> service.update("600000", "sh", body));
    }

    @Test
    void optimisticConflictIsReported() {
        StockWatchItem current = item();
        when(itemMapper.selectBySymbolMarket("600000", "sh")).thenReturn(current);
        when(itemMapper.updateAnalysisPolicy(any(), eq(0L))).thenReturn(0);

        assertThrows(StockWatchAnalysisPolicyService.AnalysisPolicyConflictException.class,
                () -> service.update("600000", "sh", policy("DAILY_CLOSE", null, 0L)));
    }

    private StockWatchItem item() {
        StockWatchItem item = new StockWatchItem();
        item.setId(1L);
        item.setSymbol("600000");
        item.setMarket("sh");
        item.setEnabled(true);
        item.setAnalysisMode("INTERVAL");
        item.setAnalysisIntervalMinutes(30);
        item.setAnomalyTriggerMode("IMMEDIATE_ANALYSIS");
        item.setWeeklyReviewEnabled(true);
        item.setMonthlyReviewEnabled(true);
        item.setAnalysisPolicyVersion(0L);
        return item;
    }

    private Map<String, Object> policy(String mode, Integer interval, long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", mode);
        body.put("intervalMinutes", interval);
        body.put("anomalyTriggerMode", "IMMEDIATE_ANALYSIS");
        body.put("weeklyReviewEnabled", false);
        body.put("monthlyReviewEnabled", false);
        body.put("expectedVersion", version);
        return body;
    }
}
