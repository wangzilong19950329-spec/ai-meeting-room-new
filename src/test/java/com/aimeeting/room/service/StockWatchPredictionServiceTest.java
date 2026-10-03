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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        StockWatchPredictionService service = new StockWatchPredictionService(mapper,
                mock(EastmoneyMarketDataService.class), mock(StockWatchV6RuntimeGate.class));
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
    void strictValidationRejectsMissingDirectionAndDuplicateHorizonsBeforeProjection() {
        StockWatchPredictionService service = new StockWatchPredictionService(
                mock(StockWatchPredictionMapper.class), mock(EastmoneyMarketDataService.class),
                mock(StockWatchV6RuntimeGate.class));
        JSONObject missingDirection = new JSONObject();
        missingDirection.put("horizon", "T1");
        missingDirection.put("targetLow", 99);
        missingDirection.put("targetHigh", 103);
        JSONArray invalid = new JSONArray();
        invalid.add(missingDirection);
        assertThrows(IllegalArgumentException.class,
                () -> service.validatePredictionsStrict(100.0, invalid));

        JSONObject first = forecast("T1", "UP", 99, 103);
        JSONObject second = forecast("T+1", "DOWN", 95, 99);
        JSONArray duplicate = new JSONArray();
        duplicate.add(first);
        duplicate.add(second);
        assertThrows(IllegalArgumentException.class,
                () -> service.validatePredictionsStrict(100.0, duplicate));
    }

    @Test
    void evaluatesOnNthFollowingTradingBar() {
        StockWatchPredictionMapper mapper = mock(StockWatchPredictionMapper.class);
        EastmoneyMarketDataService marketData = mock(EastmoneyMarketDataService.class);
        StockWatchPredictionService service = new StockWatchPredictionService(
                mapper, marketData, mock(StockWatchV6RuntimeGate.class));
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
    void fiveDaySidewaysPredictionCountsModerateInRangeMoveAsDirectionHit() {
        StockWatchPredictionMapper mapper = mock(StockWatchPredictionMapper.class);
        EastmoneyMarketDataService marketData = mock(EastmoneyMarketDataService.class);
        StockWatchPredictionService service = new StockWatchPredictionService(
                mapper, marketData, mock(StockWatchV6RuntimeGate.class));
        StockWatchPrediction prediction = new StockWatchPrediction();
        prediction.setId(51L);
        prediction.setSymbol("300750");
        prediction.setMarket("sz");
        prediction.setForecastDate(Date.valueOf("2026-08-07"));
        prediction.setHorizonCode("T5");
        prediction.setHorizonTradingDays(5);
        prediction.setSourcePrice(388.31);
        prediction.setDirection("SIDEWAYS");
        prediction.setDirectionPolicyVersion(StockWatchPredictionService.CURRENT_DIRECTION_POLICY);
        prediction.setProbability(0.6);
        prediction.setTargetLow(375.0);
        prediction.setTargetHigh(410.0);
        prediction.setInvalidationPrice(375.0);
        when(mapper.selectPending(200)).thenReturn(Collections.singletonList(prediction));
        when(marketData.fetchDailyBars("300750", "sz", 800)).thenReturn(Arrays.asList(
                bar("2026-08-10", 390, 394, 385),
                bar("2026-08-11", 393, 397, 389),
                bar("2026-08-12", 395, 398, 391),
                bar("2026-08-13", 396, 399, 392),
                bar("2026-08-14", 398.1, 400, 394)));

        service.evaluatePending();

        ArgumentCaptor<Boolean> directionHit = ArgumentCaptor.forClass(Boolean.class);
        ArgumentCaptor<Double> brierScore = ArgumentCaptor.forClass(Double.class);
        verify(mapper).updateEvaluation(eq(51L), eq("EVALUATED"), eq(Date.valueOf("2026-08-14")),
                eq(398.1), eq(400.0), eq(385.0), anyDouble(), directionHit.capture(),
                eq(true), eq(true), eq(false), anyDouble(), brierScore.capture(),
                anyString(), any(java.util.Date.class));
        assertTrue(directionHit.getValue());
        assertEquals(0.16, brierScore.getValue(), 0.0001);
    }

    @Test
    void oldPendingForecastKeepsItsOriginalProbabilityEvent() {
        StockWatchPredictionMapper mapper = mock(StockWatchPredictionMapper.class);
        EastmoneyMarketDataService marketData = mock(EastmoneyMarketDataService.class);
        StockWatchPredictionService service = new StockWatchPredictionService(
                mapper, marketData, mock(StockWatchV6RuntimeGate.class));
        StockWatchPrediction old = pendingPrediction(52L);
        old.setDirection("SIDEWAYS");
        old.setHorizonCode("T5");
        old.setHorizonTradingDays(5);
        old.setProbability(0.6);
        old.setDirectionPolicyVersion(StockWatchPredictionService.LEGACY_DIRECTION_POLICY);
        when(mapper.selectPending(200)).thenReturn(Collections.singletonList(old));
        when(marketData.fetchDailyBars("688146", "sh", 800)).thenReturn(Arrays.asList(
                bar("2026-06-19", 100, 101, 99), bar("2026-06-22", 101, 102, 100),
                bar("2026-06-23", 101, 102, 100), bar("2026-06-24", 102, 103, 101),
                bar("2026-06-25", 102.5, 103, 102)));

        service.evaluatePending();

        verify(mapper).updateEvaluation(eq(52L), eq("EVALUATED"), eq(Date.valueOf("2026-06-25")),
                eq(102.5), eq(103.0), eq(99.0), eq(2.5), eq(false), eq(true), eq(true),
                eq(false), anyDouble(), eq(0.36), anyString(), any(java.util.Date.class));
    }

    @Test
    void strictSavePersistsPolicyFromFrozenRequest() {
        StockWatchPredictionMapper mapper = mock(StockWatchPredictionMapper.class);
        StockWatchPredictionService service = new StockWatchPredictionService(
                mapper, mock(EastmoneyMarketDataService.class), mock(StockWatchV6RuntimeGate.class));
        JSONArray forecasts = new JSONArray();
        forecasts.add(forecast("T5", "SIDEWAYS", 99, 103));

        service.savePredictionsStrict(9L, "688146", "sh", "中船特气",
                LocalDate.of(2026, 6, 19), 100.0, forecasts, "{}",
                StockWatchPredictionService.LEGACY_DIRECTION_POLICY);

        ArgumentCaptor<StockWatchPrediction> captor = ArgumentCaptor.forClass(StockWatchPrediction.class);
        verify(mapper).insert(captor.capture());
        assertEquals(StockWatchPredictionService.LEGACY_DIRECTION_POLICY,
                captor.getValue().getDirectionPolicyVersion());
    }

    @Test
    void historySeparatesOriginalEvaluationFromCurrentPolicyReview() {
        StockWatchPredictionMapper mapper = mock(StockWatchPredictionMapper.class);
        StockWatchPredictionService service = new StockWatchPredictionService(
                mapper, mock(EastmoneyMarketDataService.class), mock(StockWatchV6RuntimeGate.class));
        StockWatchPrediction legacy = evaluatedPrediction(51L, "T5", "SIDEWAYS", 2.521182, false, 0.36);
        legacy.setActualClose(398.1);
        legacy.setSourcePrice(388.31);
        legacy.setRangeCloseHit(true);
        legacy.setEvaluationNote("实际方向=UP，预测方向=SIDEWAYS，区间收盘命中=true");
        when(mapper.selectRecent("300750", "sz", 200)).thenReturn(Collections.singletonList(legacy));

        Map<String, Object> history = service.history("300750", "sz", 200);
        Map<String, Object> summary = (Map<String, Object>) history.get("summary");
        Map<String, Object> row = ((List<Map<String, Object>>) history.get("records")).get(0);

        assertEquals(0.0, summary.get("directionHitRate"));
        assertEquals(100.0, summary.get("currentPolicyDirectionHitRate"));
        assertEquals(0.0, summary.get("strictDirectionHitRate"));
        assertEquals(0.36, summary.get("averageBrierScore"));
        assertEquals(true, row.get("currentPolicyDirectionHit"));
        assertEquals(false, row.get("directionHit"));
        assertEquals(false, row.get("strictDirectionHit"));
        assertEquals(0.36, row.get("brierScore"));
        assertEquals(true, row.get("rangeCloseHit"));
        assertEquals("实际方向=UP，预测方向=SIDEWAYS，区间收盘命中=true", row.get("evaluationNote"));
    }

    @Test
    void currentPolicyReviewUsesHorizonBoundariesAndLeavesMissingReturnsUnscored() {
        StockWatchPredictionMapper mapper = mock(StockWatchPredictionMapper.class);
        StockWatchPredictionService service = new StockWatchPredictionService(
                mapper, mock(EastmoneyMarketDataService.class), mock(StockWatchV6RuntimeGate.class));
        StockWatchPrediction t1 = evaluatedPrediction(1L, "T1", "SIDEWAYS", 1.5, false, 0.25);
        StockWatchPrediction t5 = evaluatedPrediction(2L, "T5", "UP", 3.000001, true, 0.25);
        StockWatchPrediction w1 = evaluatedPrediction(3L, "W1", "SIDEWAYS", -3.0, false, 0.25);
        StockWatchPrediction m1 = evaluatedPrediction(4L, "M1", "SIDEWAYS", 5.0, false, 0.25);
        StockWatchPrediction missing = evaluatedPrediction(5L, "T1", "SIDEWAYS", null, false, 0.25);
        when(mapper.selectRecent("300750", "sz", 200)).thenReturn(Arrays.asList(t1, t5, w1, m1, missing));

        Map<String, Object> history = service.history("300750", "sz", 200);
        Map<String, Object> summary = (Map<String, Object>) history.get("summary");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) history.get("records");

        assertEquals(4, summary.get("currentPolicyScored"));
        assertEquals(100.0, summary.get("currentPolicyDirectionHitRate"));
        assertEquals(true, rows.get(0).get("currentPolicyDirectionHit"));
        assertEquals(true, rows.get(1).get("currentPolicyDirectionHit"));
        assertEquals(true, rows.get(2).get("currentPolicyDirectionHit"));
        assertEquals(true, rows.get(3).get("currentPolicyDirectionHit"));
        assertNull(rows.get(4).get("currentPolicyDirectionHit"));
        assertFalse(Boolean.TRUE.equals(rows.get(4).get("strictDirectionHit")));
    }

    @Test
    void mixedGenerationPoliciesDoNotProduceMisleadingAggregateBrier() {
        StockWatchPredictionMapper mapper = mock(StockWatchPredictionMapper.class);
        StockWatchPredictionService service = new StockWatchPredictionService(
                mapper, mock(EastmoneyMarketDataService.class), mock(StockWatchV6RuntimeGate.class));
        StockWatchPrediction old = evaluatedPrediction(1L, "T5", "SIDEWAYS", 2.5, false, 0.36);
        StockWatchPrediction current = evaluatedPrediction(2L, "T5", "SIDEWAYS", 2.5, true, 0.16);
        current.setDirectionPolicyVersion(StockWatchPredictionService.CURRENT_DIRECTION_POLICY);
        when(mapper.selectRecent("300750", "sz", 200)).thenReturn(Arrays.asList(old, current));

        Map<String, Object> summary = (Map<String, Object>) service.history("300750", "sz", 200).get("summary");

        assertEquals(true, summary.get("brierPolicyMixed"));
        assertNull(summary.get("averageBrierScore"));
        assertEquals(100.0, summary.get("currentPolicyDirectionHitRate"));
    }

    private StockWatchPrediction evaluatedPrediction(Long id, String horizon, String direction,
                                                      Double actualReturn, boolean originalHit, double brier) {
        StockWatchPrediction prediction = new StockWatchPrediction();
        prediction.setId(id);
        prediction.setForecastDate(Date.valueOf("2026-08-07"));
        prediction.setHorizonCode(horizon);
        prediction.setDirection(direction);
        prediction.setProbability(0.5);
        prediction.setActualReturnPct(actualReturn);
        prediction.setDirectionHit(originalHit);
        prediction.setBrierScore(brier);
        prediction.setStatus("EVALUATED");
        return prediction;
    }

    @Test
    void cachesSourceFailuresWithinCurrentBatch() {
        StockWatchPredictionMapper mapper = mock(StockWatchPredictionMapper.class);
        EastmoneyMarketDataService marketData = mock(EastmoneyMarketDataService.class);
        StockWatchPredictionService service = new StockWatchPredictionService(
                mapper, marketData, mock(StockWatchV6RuntimeGate.class));
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

    private JSONObject forecast(String horizon, String direction, double targetLow, double targetHigh) {
        JSONObject forecast = new JSONObject();
        forecast.put("horizon", horizon);
        forecast.put("direction", direction);
        forecast.put("targetLow", targetLow);
        forecast.put("targetHigh", targetHigh);
        return forecast;
    }
}
