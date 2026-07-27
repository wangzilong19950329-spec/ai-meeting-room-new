package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchAlertDeliveryObligationMapper;
import com.aimeeting.room.dao.StockWatchAnalysisDemandMapper;
import com.aimeeting.room.dao.StockWatchAnalysisTaskMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.entity.StockWatchAnalysisDemand;
import com.aimeeting.room.entity.StockWatchItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockWatchAnalysisDemandServiceTest {

    @Mock private StockWatchAnalysisDemandMapper demandMapper;
    @Mock private StockWatchAlertDeliveryObligationMapper obligationMapper;
    @Mock private StockWatchAnalysisTaskMapper taskMapper;
    @Mock private StockWatchTechnicalAnalysisMapper analysisMapper;
    @Mock private StockWatchTechnicalAnalysisService technicalAnalysisService;
    @Mock private StockWatchTechnicalEmailRenderer emailRenderer;
    @Mock private StockWatchAnalysisPolicyService policyService;
    @Mock private StockWatchAnalysisSchedulerOwnershipService ownershipService;

    private StockWatchAnalysisSchedulePlanner planner;
    private StockWatchAnalysisDemandService service;

    @BeforeEach
    void setUp() {
        planner = new StockWatchAnalysisSchedulePlanner();
        service = new StockWatchAnalysisDemandService(demandMapper, obligationMapper, taskMapper,
                analysisMapper, technicalAnalysisService, emailRenderer, policyService, ownershipService, planner);
    }

    @Test
    void createScheduledFreezesPolicyVersionAndSlot() {
        StockWatchItem item = item("002979", "sz", "DAILY_CLOSE", null, 7L);
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 17, 15, 20, 0, 0,
                StockWatchAnalysisSchedulePlanner.CHINA_ZONE);
        StockWatchAnalysisSchedulePlanner.DueDecision decision = planner.currentDayDecision(item, now);
        when(policyService.allowsAutomaticDemand(item, "DAILY_CLOSE")).thenReturn(true);
        when(demandMapper.selectByDemandKey(anyString())).thenReturn(null);
        when(demandMapper.selectActiveByCoalesceKey(anyString())).thenReturn(null);

        StockWatchAnalysisDemandService.DemandCreationResult result = service.createScheduled(item, decision);

        assertTrue(result.isCreated());
        ArgumentCaptor<StockWatchAnalysisDemand> captor = ArgumentCaptor.forClass(StockWatchAnalysisDemand.class);
        verify(demandMapper).insert(captor.capture());
        StockWatchAnalysisDemand demand = captor.getValue();
        assertEquals("stock.unified-analysis.v1", demand.getCapability());
        assertEquals("DAILY_CLOSE", demand.getTriggerType());
        assertEquals("DAY:2026-07-17:DAILY_CLOSE", demand.getScheduleSlotKey());
        assertEquals("15:00", demand.getRunSlot());
        assertEquals(7L, demand.getSourcePolicyVersion());
        assertEquals("PENDING", demand.getStatus());
    }

    @Test
    void createScheduledKeepsDeferredAnomalyAsNextWindowAnalysis() {
        StockWatchItem item = item("002979", "sz", "INTERVAL", 30, 7L);
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 17, 10, 0, 0, 0,
                StockWatchAnalysisSchedulePlanner.CHINA_ZONE);
        StockWatchAnalysisSchedulePlanner.DueDecision decision = planner.currentDayDecision(item, now);
        StockWatchAnalysisDemand deferred = anomalyDemand("PENDING");
        deferred.setDemandKey("deferred-anomaly-key");
        deferred.setTriggerType("ANOMALY_DEFERRED");
        deferred.setSourcePolicyVersion(7L);
        when(policyService.allowsAutomaticDemand(item, "INTERVAL")).thenReturn(true);
        when(demandMapper.selectByDemandKey(anyString())).thenReturn(null);
        when(demandMapper.selectActiveByCoalesceKey(anyString())).thenReturn(deferred);

        StockWatchAnalysisDemandService.DemandCreationResult result = service.createScheduled(item, decision);

        assertFalse(result.isCreated());
        assertSame(deferred, result.getDemand());
        verify(demandMapper, never()).markSupersededIfUnsubmitted(any(), anyString(), any(Date.class));
        verify(demandMapper, never()).insert(any());
    }

    @Test
    void createScheduledReusesActiveDemandWhenUniqueActiveCoalesceWinsRace() {
        StockWatchItem item = item("002979", "sz", "DAILY_CLOSE", null, 7L);
        ZonedDateTime now = ZonedDateTime.of(2026, 7, 17, 15, 20, 0, 0,
                StockWatchAnalysisSchedulePlanner.CHINA_ZONE);
        StockWatchAnalysisSchedulePlanner.DueDecision decision = planner.currentDayDecision(item, now);
        StockWatchAnalysisDemand racedActive = anomalyDemand("PENDING");
        racedActive.setDemandKey("raced-active-key");
        when(policyService.allowsAutomaticDemand(item, "DAILY_CLOSE")).thenReturn(true);
        when(demandMapper.selectByDemandKey(anyString())).thenReturn(null);
        when(demandMapper.selectActiveByCoalesceKey(anyString())).thenReturn(null, racedActive);
        doThrow(new org.springframework.dao.DuplicateKeyException("active coalesce race"))
                .when(demandMapper).insert(any(StockWatchAnalysisDemand.class));

        StockWatchAnalysisDemandService.DemandCreationResult result = service.createScheduled(item, decision);

        assertFalse(result.isCreated());
        assertSame(racedActive, result.getDemand());
    }

    @Test
    void dispatchDoesNotReserveProviderSubmissionBeforeCanonicalTaskExists() {
        StockWatchAnalysisDemand candidate = anomalyDemand("PENDING");
        StockWatchAnalysisDemand claimed = anomalyDemand("CLAIMED");
        StockWatchItem item = item("002979", "sz", "INTERVAL", 30, 7L);
        when(demandMapper.selectDispatchable(any(Date.class), eq(1))).thenReturn(List.of(candidate));
        when(demandMapper.claim(eq(1L), eq(0L), anyString(), any(Date.class), any(Date.class))).thenReturn(1);
        when(demandMapper.selectById(1L)).thenReturn(claimed);
        when(policyService.currentItem("002979", "sz")).thenReturn(item);
        when(policyService.allowsAutomaticDemand(item, "ANOMALY_IMMEDIATE")).thenReturn(true);
        when(technicalAnalysisService.runDemand(claimed)).thenThrow(new IllegalStateException("bridge_down"));
        when(taskMapper.selectByObservation("002979", "sz", "DAY", "DAY:2026-07-17:alert:fact-key-001"))
                .thenReturn(Collections.emptyList());
        when(demandMapper.selectSubmitted(20)).thenReturn(Collections.emptyList());

        service.reconcile();

        verify(demandMapper, never()).reserveProviderSubmission(any(), anyString());
        verify(demandMapper).releaseForRetry(eq(1L), anyString(), any(Date.class),
                startsWith("unified_analysis_submit_retry:bridge_down"));
    }

    private StockWatchItem item(String symbol, String market, String mode, Integer intervalMinutes, Long version) {
        StockWatchItem item = new StockWatchItem();
        item.setSymbol(symbol);
        item.setMarket(market);
        item.setStockName("雷赛智能");
        item.setEnabled(true);
        item.setAnalysisMode(mode);
        item.setAnalysisIntervalMinutes(intervalMinutes);
        item.setAnomalyTriggerMode("IMMEDIATE_ANALYSIS");
        item.setWeeklyReviewEnabled(true);
        item.setMonthlyReviewEnabled(true);
        item.setAnalysisPolicyVersion(version);
        return item;
    }

    private StockWatchAnalysisDemand anomalyDemand(String status) {
        StockWatchAnalysisDemand demand = new StockWatchAnalysisDemand();
        demand.setId(1L);
        demand.setSymbol("002979");
        demand.setMarket("sz");
        demand.setStockName("雷赛智能");
        demand.setCapability("stock.unified-analysis.v1");
        demand.setTriggerType("ANOMALY_IMMEDIATE");
        demand.setPeriodType("DAY");
        demand.setTargetFactObservationId(9001L);
        demand.setTargetFactObservationKey("fact-key-001");
        demand.setTargetSourceTimeEpochMs(ZonedDateTime.of(2026, 7, 17, 10, 30, 0, 0,
                StockWatchAnalysisSchedulePlanner.CHINA_ZONE).toInstant().toEpochMilli());
        demand.setTradeDate(java.sql.Date.valueOf("2026-07-17"));
        demand.setRunSlot("alert:fact-key-001");
        demand.setSourcePolicyVersion(7L);
        demand.setStatus(status);
        demand.setRowVersion(0L);
        demand.setDeadlineAt(new Date(System.currentTimeMillis() + 30L * 60L * 1000L));
        return demand;
    }
}
