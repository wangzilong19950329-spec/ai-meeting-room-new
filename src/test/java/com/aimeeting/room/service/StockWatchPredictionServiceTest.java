package com.aimeeting.room.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchPredictionMapper;
import com.aimeeting.room.entity.StockWatchPrediction;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockWatchPredictionServiceTest {

    @Test
    void persistsOnlyValidStructuredForecasts() {
        StockWatchPredictionMapper mapper = mock(StockWatchPredictionMapper.class);
        StockWatchPredictionService service = new StockWatchPredictionService(mapper, mock(EastmoneyMarketDataService.class));
        JSONObject item = new JSONObject();
        item.put("horizon", "T+1");
        item.put("direction", "UP");
        item.put("probability", 0.7);
        item.put("targetLow", 101);
        item.put("targetHigh", 105);
        item.put("invalidationPrice", 98);
        item.put("rationale", "量价确认");
        JSONArray forecasts = new JSONArray();
        forecasts.add(item);

        List<StockWatchPrediction> saved = service.savePredictions(9L, "688146", "sh", "中船特气",
                LocalDate.of(2026, 6, 19), 100.0, forecasts, "{}");

        assertEquals(1, saved.size());
        ArgumentCaptor<StockWatchPrediction> captor = ArgumentCaptor.forClass(StockWatchPrediction.class);
        verify(mapper).insert(captor.capture());
        assertEquals("T1", captor.getValue().getHorizonCode());
        assertEquals(1.0, captor.getValue().getExpectedReturnLow(), 0.0001);
        assertEquals(5.0, captor.getValue().getExpectedReturnHigh(), 0.0001);
    }

    @Test
    void evaluatesOnNthFollowingTradingBar() {
        StockWatchPredictionMapper mapper = mock(StockWatchPredictionMapper.class);
        EastmoneyMarketDataService marketData = mock(EastmoneyMarketDataService.class);
        StockWatchPredictionService service = new StockWatchPredictionService(mapper, marketData);
        StockWatchPrediction prediction = new StockWatchPrediction();
        prediction.setId(1L);
        prediction.setSymbol("688146");
        prediction.setMarket("sh");
        prediction.setForecastDate(Date.valueOf("2026-06-18"));
        prediction.setHorizonCode("T1");
        prediction.setHorizonTradingDays(1);
        prediction.setSourcePrice(100.0);
        prediction.setDirection("UP");
        prediction.setProbability(0.7);
        prediction.setTargetLow(101.0);
        prediction.setTargetHigh(105.0);
        prediction.setInvalidationPrice(98.0);
        when(mapper.selectPending(200)).thenReturn(Collections.singletonList(prediction));

        List<Map<String, Object>> bars = new ArrayList<>();
        bars.add(bar("2026-06-18", 100, 101, 99));
        bars.add(bar("2026-06-19", 103, 104, 100));
        when(marketData.fetchDailyBars("688146", "sh", 800)).thenReturn(bars);

        service.evaluatePending();

        verify(mapper).updateEvaluation(eq(1L), eq("EVALUATED"), eq(Date.valueOf("2026-06-19")),
                eq(103.0), eq(104.0), eq(100.0), eq(3.0), eq(true), eq(true), eq(true), eq(false),
                eq(0.0), eq(0.09), anyString(), any(java.util.Date.class));
    }

    @Test
    void cachesSourceFailuresWithinCurrentBatch() {
        StockWatchPredictionMapper mapper = mock(StockWatchPredictionMapper.class);
        EastmoneyMarketDataService marketData = mock(EastmoneyMarketDataService.class);
        StockWatchPredictionService service = new StockWatchPredictionService(mapper, marketData);
        StockWatchPrediction first = pendingPrediction(1L);
        StockWatchPrediction second = pendingPrediction(2L);
        when(mapper.selectPending(200)).thenReturn(Arrays.asList(first, second));
        when(marketData.fetchDailyBars("688146", "sh", 800)).thenThrow(new IllegalStateException("source down"));

        service.evaluatePending();

        verify(marketData, times(1)).fetchDailyBars("688146", "sh", 800);
        verify(mapper, never()).updateEvaluation(anyLong(), anyString(), any(Date.class),
                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyBoolean(), anyBoolean(),
                anyBoolean(), anyBoolean(), anyDouble(), anyDouble(), anyString(), any(java.util.Date.class));
    }

    private StockWatchPrediction pendingPrediction(Long id) {
        StockWatchPrediction prediction = new StockWatchPrediction();
        prediction.setId(id);
        prediction.setSymbol("688146");
        prediction.setMarket("sh");
        prediction.setForecastDate(Date.valueOf("2026-06-18"));
        prediction.setHorizonCode("T1");
        prediction.setHorizonTradingDays(1);
        prediction.setSourcePrice(100.0);
        prediction.setDirection("UP");
        prediction.setProbability(0.7);
        prediction.setTargetLow(101.0);
        prediction.setTargetHigh(105.0);
        prediction.setInvalidationPrice(98.0);
        return prediction;
    }

    private Map<String, Object> bar(String date, double close, double high, double low) {
        Map<String, Object> bar = new LinkedHashMap<>();
        bar.put("date", date);
        bar.put("close", close);
        bar.put("high", high);
        bar.put("low", low);
        return bar;
    }
}
