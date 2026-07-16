package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchFactHeadMapper;
import com.aimeeting.room.dao.StockWatchFactObservationMapper;
import com.aimeeting.room.dao.StockWatchIntentTestMapper;
import com.aimeeting.room.dao.StockWatchIntentTestResultMapper;
import com.aimeeting.room.entity.StockWatchFactHead;
import com.aimeeting.room.entity.StockWatchFactObservation;
import com.aimeeting.room.service.StockWatchRiskPatternService.RiskPatternResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
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

    private StockWatchFactHead head() {
        StockWatchFactHead head = new StockWatchFactHead();
        head.setId(1L);
        head.setArrivalVersion(0L);
        head.setRowVersion(0L);
        return head;
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
        return new RiskPatternResult(observations, Collections.emptyMap(), now(), true);
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
