package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchFactHeadMapper;
import com.aimeeting.room.dao.StockWatchFactObservationMapper;
import com.aimeeting.room.dao.StockWatchIntentTestMapper;
import com.aimeeting.room.dao.StockWatchIntentTestResultMapper;
import com.aimeeting.room.entity.StockWatchFactHead;
import com.aimeeting.room.entity.StockWatchFactObservation;
import com.aimeeting.room.entity.StockWatchIntentTest;
import com.aimeeting.room.service.StockWatchRiskPatternService.RiskPatternResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockWatchFactPersistenceServiceTest {

    @Mock StockWatchFactHeadMapper headMapper;
    @Mock StockWatchFactObservationMapper observationMapper;
    @Mock StockWatchIntentTestMapper intentTestMapper;
    @Mock StockWatchIntentTestResultMapper resultMapper;

    private StockWatchFactPersistenceService service;

    @BeforeEach
    void setUp() {
        service = new StockWatchFactPersistenceService(headMapper, observationMapper, intentTestMapper, resultMapper);
    }

    @Test
    void existingTestsAreEvaluatedBeforeCurrentObservationCreatesNewTests() {
        StockWatchFactHead head = head();
        when(headMapper.selectForUpdate("002979", "sz")).thenReturn(head);
        when(observationMapper.insert(any())).thenAnswer(invocation -> {
            ((StockWatchFactObservation) invocation.getArgument(0)).setId(11L);
            return 1;
        });
        when(headMapper.updateLatest(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString()))
                .thenReturn(1);
        when(intentTestMapper.selectActiveForUpdate("002979", "sz", 11L)).thenReturn(Collections.emptyList());
        when(intentTestMapper.insert(any())).thenReturn(1);

        Map<String, Object> receipt = service.observe(
                "002979", "sz", "雷赛智能", quote(), triggeredRisk(), now()).toMap();

        assertEquals("CREATED", receipt.get("status"));
        assertEquals(1, receipt.get("createdTestCount"));
        ArgumentCaptor<StockWatchFactObservation> observationCaptor =
                ArgumentCaptor.forClass(StockWatchFactObservation.class);
        verify(observationMapper).insert(observationCaptor.capture());
        JSONObject snapshot = JSON.parseObject(observationCaptor.getValue().getFactSnapshotJson());
        assertNotNull(snapshot.getJSONObject("priceFailureResearchFeatures"));
        assertEquals(12.0, snapshot.getJSONObject("priceFailureResearchFeatures").getDouble("return5Pct"));
        assertFalse(snapshot.getJSONObject("riskPatternObservations")
                .getJSONObject("derived").containsKey("return5Pct"));
        InOrder order = inOrder(intentTestMapper);
        order.verify(intentTestMapper).selectActiveForUpdate("002979", "sz", 11L);
        order.verify(intentTestMapper).insert(any());
    }

    @Test
    void duplicateObservationDoesNotLockHeadOrAdvanceTests() {
        StockWatchFactObservation existing = new StockWatchFactObservation();
        existing.setId(7L);
        existing.setObservationKey("existing");
        when(observationMapper.selectByObservationKey(anyString())).thenReturn(existing);

        Map<String, Object> receipt = service.observe(
                "002979", "sz", "雷赛智能", quote(), triggeredRisk(), now()).toMap();

        assertEquals("DUPLICATE", receipt.get("status"));
        verify(headMapper, never()).selectForUpdate(anyString(), anyString());
        verify(intentTestMapper, never()).selectActiveForUpdate(anyString(), anyString(), anyLong());
    }

    @Test
    void quoteAlertObservationUsesStrictQuoteTimestampAndDoesNotCreateIntentTests() {
        StockWatchFactHead head = head();
        when(headMapper.selectForUpdate("002979", "sz")).thenReturn(head);
        when(observationMapper.insert(any())).thenAnswer(invocation -> {
            ((StockWatchFactObservation) invocation.getArgument(0)).setId(21L);
            return 1;
        });
        when(headMapper.updateLatest(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                anyString(), anyString(), anyString())).thenReturn(1);

        Map<String, Object> receipt = service.observeQuoteAlert(
                "002979", "sz", "雷赛智能", quote(), now()).toMap();

        assertEquals("CREATED", receipt.get("status"));
        assertEquals("QUOTE_ALERT", receipt.get("sourceTimeQuality"));
        assertEquals("2026-07-16", receipt.get("sourceDate"));
        assertEquals(0, receipt.get("createdTestCount"));
        ArgumentCaptor<StockWatchFactObservation> observationCaptor =
                ArgumentCaptor.forClass(StockWatchFactObservation.class);
        verify(observationMapper).insert(observationCaptor.capture());
        StockWatchFactObservation observation = observationCaptor.getValue();
        assertEquals(StockWatchFactPersistenceService.QUOTE_ALERT_SOURCE_TIME_QUALITY,
                observation.getSourceTimeQuality());
        JSONObject snapshot = JSON.parseObject(observation.getFactSnapshotJson());
        assertEquals(StockWatchFactPersistenceService.QUOTE_ALERT_FACT_SCHEMA, snapshot.getString("schema"));
        assertNotNull(snapshot.getJSONObject("quote"));
        assertNotNull(snapshot.getJSONObject("quoteAlertObservation"));
        assertFalse(snapshot.containsKey("riskPatternObservations"));
        verify(intentTestMapper, never()).selectActiveForUpdate(anyString(), anyString(), anyLong());
        verify(intentTestMapper, never()).insert(any());
    }

    @Test
    void quoteAlertObservationRejectsMissingQuoteTimestamp() {
        Map<String, Object> quote = quote();
        quote.remove("timestamp");

        Map<String, Object> receipt = service.observeQuoteAlert(
                "002979", "sz", "雷赛智能", quote, now()).toMap();

        assertEquals("SKIPPED", receipt.get("status"));
        assertEquals("quote_alert_timestamp_missing", receipt.get("reason"));
        verify(observationMapper, never()).insert(any());
        verify(headMapper, never()).selectForUpdate(anyString(), anyString());
    }

    @Test
    void quoteAlertObservationIdentityIgnoresSamplingTime() {
        StockWatchFactHead firstHead = head();
        StockWatchFactHead secondHead = head();
        secondHead.setArrivalVersion(1L);
        secondHead.setRowVersion(1L);
        when(headMapper.selectForUpdate("002979", "sz")).thenReturn(firstHead, secondHead);
        when(observationMapper.insert(any())).thenAnswer(invocation -> {
            ((StockWatchFactObservation) invocation.getArgument(0)).setId(21L);
            return 1;
        });
        when(headMapper.updateLatest(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                anyString(), anyString(), anyString())).thenReturn(1);

        ZonedDateTime sampledAt = now();
        service.observeQuoteAlert("002979", "sz", "雷赛智能", quote(), sampledAt);
        service.observeQuoteAlert("002979", "sz", "雷赛智能", quote(), sampledAt.plusMinutes(3));

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(observationMapper, times(2)).selectByObservationKey(keyCaptor.capture());
        assertEquals(keyCaptor.getAllValues().get(0), keyCaptor.getAllValues().get(1));
    }

    @Test
    void researchFeaturesDoNotChangeCanonicalObservationIdentity() {
        StockWatchFactHead head = head();
        when(headMapper.selectForUpdate("002979", "sz")).thenReturn(head);
        when(observationMapper.insert(any())).thenAnswer(invocation -> {
            ((StockWatchFactObservation) invocation.getArgument(0)).setId(11L);
            return 1;
        });
        when(headMapper.updateLatest(anyLong(), anyLong(), anyLong(), anyLong(), anyLong(),
                anyString(), anyString(), anyString())).thenReturn(1);
        when(intentTestMapper.selectActiveForUpdate(anyString(), anyString(), anyLong()))
                .thenReturn(Collections.emptyList());

        service.observe("002979", "sz", "雷赛智能", quote(), riskWithResearchReturn5(8.0), now());
        service.observe("002979", "sz", "雷赛智能", quote(), riskWithResearchReturn5(18.0), now());

        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        verify(observationMapper, times(2)).selectByObservationKey(keyCaptor.capture());
        assertEquals(keyCaptor.getAllValues().get(0), keyCaptor.getAllValues().get(1));
    }

    @Test
    void sameTradingDayObservationDoesNotAgeOrPrematurelySupportIntentTest() {
        StockWatchFactObservation observation = observation(12L, 2L, "2026-07-16");
        StockWatchIntentTest test = intentTest("2026-07-16", 1, 1);
        when(intentTestMapper.selectActiveForUpdate("002979", "sz", 12L))
                .thenReturn(Collections.singletonList(test));
        when(intentTestMapper.updateProgress(8L, 0L, 1, 1, "UNRESOLVED", 12L,
                "2026-07-16", null)).thenReturn(1);

        List<?> verdicts = ReflectionTestUtils.invokeMethod(service, "evaluateExistingTests", observation,
                riskWithDailyReturn(0.5));

        assertEquals(1, verdicts.size());
        verify(intentTestMapper).updateProgress(8L, 0L, 1, 1, "UNRESOLVED", 12L,
                "2026-07-16", null);
    }

    @Test
    void intentTestSupportsOnlyWhenTradingDayWindowAndCoverageMature() {
        StockWatchFactObservation observation = observation(13L, 3L, "2026-07-17");
        StockWatchIntentTest test = intentTest("2026-07-16", 2, 2);
        when(intentTestMapper.selectActiveForUpdate("002979", "sz", 13L))
                .thenReturn(Collections.singletonList(test));
        when(intentTestMapper.updateProgress(8L, 0L, 3, 3, "SUPPORTED", 13L,
                "2026-07-17", 13L)).thenReturn(1);

        List<?> verdicts = ReflectionTestUtils.invokeMethod(service, "evaluateExistingTests", observation,
                riskWithDailyReturn(0.5));

        assertEquals(1, verdicts.size());
        verify(intentTestMapper).updateProgress(8L, 0L, 3, 3, "SUPPORTED", 13L,
                "2026-07-17", 13L);
    }

    @Test
    void modelIntentTestsAreBoundToAnalysisTaskRatherThanSharedObservation() {
        when(intentTestMapper.insert(any())).thenReturn(1);
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("intent", "ACCUMULATE");
        intent.put("timeWindow", "FIVE_DAY");

        assertEquals(2, service.registerModelIntentTests(21L, 101L, "002979", "sz", intent));
        assertEquals(2, service.registerModelIntentTests(21L, 102L, "002979", "sz", intent));

        ArgumentCaptor<StockWatchIntentTest> captor = ArgumentCaptor.forClass(StockWatchIntentTest.class);
        verify(intentTestMapper, org.mockito.Mockito.times(4)).insert(captor.capture());
        Set<String> claimRefs = captor.getAllValues().stream()
                .map(StockWatchIntentTest::getClaimRef).collect(Collectors.toSet());
        assertEquals(Set.of("analysisTask:101:mainForceIntent", "analysisTask:102:mainForceIntent"), claimRefs);
        assertEquals(4, captor.getAllValues().stream().map(StockWatchIntentTest::getTestKey)
                .collect(Collectors.toSet()).size());
        assertTrue(captor.getAllValues().stream().allMatch(test -> test.getMaxObservationAge() == 5));
        assertTrue(captor.getAllValues().stream().allMatch(test -> test.getRequiredObservedCount() == 5));
    }

    private StockWatchFactHead head() {
        StockWatchFactHead head = new StockWatchFactHead();
        head.setId(1L);
        head.setArrivalVersion(0L);
        head.setRowVersion(0L);
        return head;
    }

    private StockWatchFactObservation observation(Long id, Long version, String sourceDate) {
        StockWatchFactObservation observation = new StockWatchFactObservation();
        observation.setId(id);
        observation.setArrivalVersion(version);
        observation.setSymbol("002979");
        observation.setMarket("sz");
        observation.setSourceDate(sourceDate);
        return observation;
    }

    private StockWatchIntentTest intentTest(String lastSourceDate, int age, int observed) {
        StockWatchIntentTest test = new StockWatchIntentTest();
        test.setId(8L);
        test.setMetricPath("dailyReturnPct");
        test.setComparisonOperator("LE");
        test.setTargetValueJson("-1.0");
        test.setMaxObservationAge(3);
        test.setRequiredObservedCount(3);
        test.setAgeCount(age);
        test.setObservedCount(observed);
        test.setLastEvaluatedSourceDate(lastSourceDate);
        test.setRowVersion(0L);
        return test;
    }

    private RiskPatternResult riskWithDailyReturn(double dailyReturn) {
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("dailyReturnPct", dailyReturn);
        Map<String, Object> observations = new LinkedHashMap<>();
        observations.put("derived", derived);
        observations.put("patterns", Collections.emptyList());
        return new RiskPatternResult(observations, Collections.emptyMap(), now(), false);
    }

    private RiskPatternResult triggeredRisk() {
        Map<String, Object> sourceQuality = new LinkedHashMap<>();
        sourceQuality.put("freshnessStatus", "FRESH");
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("close", 99.0);
        derived.put("breakoutHigh20", 100.0);
        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("patternId", "R01");
        pattern.put("status", "triggered");
        Map<String, Object> observations = new LinkedHashMap<>();
        observations.put("sourceQuality", sourceQuality);
        observations.put("derived", derived);
        observations.put("patterns", Collections.singletonList(pattern));
        Map<String, Object> research = new LinkedHashMap<>();
        research.put("return5Pct", 12.0);
        return new RiskPatternResult(observations, Collections.emptyMap(), now(), true, research);
    }

    private RiskPatternResult riskWithResearchReturn5(double return5) {
        RiskPatternResult baseline = triggeredRisk();
        Map<String, Object> research = new LinkedHashMap<>(baseline.getPriceFailureResearchFeatures());
        research.put("return5Pct", return5);
        return new RiskPatternResult(baseline.getObservations(), baseline.getGuidance(),
                baseline.getQuoteObservedAt(), baseline.isActive(), research);
    }

    private Map<String, Object> quote() {
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("ok", true);
        quote.put("symbol", "002979");
        quote.put("market", "sz");
        quote.put("timestamp", now().toEpochSecond());
        quote.put("price", 99.0);
        return quote;
    }

    private ZonedDateTime now() {
        return ZonedDateTime.of(2026, 7, 16, 10, 0, 0, 0, ZoneId.of("Asia/Shanghai"));
    }
}
