package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchFactObservationMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.dao.StockWatchTechnicalSessionMapper;
import com.aimeeting.room.entity.StockWatchAnalysisTask;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import com.aimeeting.room.entity.StockWatchTechnicalSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockWatchDirectionPolicyProvenanceTest {

    @Test
    void newPromptAndFrozenProjectionCarryTheSameDirectionPolicy() {
        StockWatchTechnicalAnalysisMapper mapper = mock(StockWatchTechnicalAnalysisMapper.class);
        StockWatchTechnicalAnalysisService service = service(mapper);
        when(mapper.selectRecentBySymbol(eq("300750"), eq("sz"), eq("DAY"), eq(2)))
                .thenReturn(Collections.emptyList());
        StockWatchTechnicalSession session = session();
        Object ref = ReflectionTestUtils.invokeMethod(service, "normalizeStockRef", "300750", "sz");
        Object window = ReflectionTestUtils.invokeMethod(service, "currentPeriodWindow",
                "DAY", LocalDate.of(2026, 8, 7));

        String prompt = ReflectionTestUtils.invokeMethod(service, "buildPrompt",
                ref, "宁德时代", Collections.emptyMap(), Collections.emptyMap(),
                new JSONObject(true), new JSONObject(true), Collections.emptyList(),
                session, "manual_api", "DAY", window,
                Collections.<StockWatchTechnicalAnalysis>emptyList(), null);

        assertTrue(prompt.contains("T1震荡为±1.5%"));
        assertTrue(prompt.contains("T5/W1震荡为±3.0%"));
        assertTrue(prompt.contains("M1震荡为±5.0%"));
        assertTrue(prompt.contains("覆盖会话历史中的旧口径"));
        assertEquals(StockWatchPredictionService.CURRENT_DIRECTION_POLICY,
                projection(service).getString("directionPolicyVersion"));
    }

    @Test
    void replayOfFrozenOldRequestKeepsLegacyPolicy() {
        StockWatchTechnicalAnalysisService service = service(mock(StockWatchTechnicalAnalysisMapper.class));
        JSONObject projection = projection(service);
        assertEquals(StockWatchPredictionService.CURRENT_DIRECTION_POLICY,
                restoredPolicy(service, projection));

        projection.remove("directionPolicyVersion");

        assertEquals(StockWatchPredictionService.LEGACY_DIRECTION_POLICY,
                restoredPolicy(service, projection));
    }

    private String restoredPolicy(StockWatchTechnicalAnalysisService service, JSONObject projection) {
        String projectionJson = projection.toJSONString();
        JSONObject envelope = new JSONObject(true);
        envelope.put("projectionJson", projectionJson);
        envelope.put("projectionDigest", ReflectionTestUtils.invokeMethod(service, "sha256", projectionJson));
        StockWatchAnalysisTask task = new StockWatchAnalysisTask();
        task.setRequestDigest("frozen-request-digest");
        task.setPeriodType("DAY");
        task.setRequestEnvelopeJson(envelope.toJSONString());
        Object frozen = ReflectionTestUtils.invokeMethod(service, "restoreFrozenProjection",
                StockWatchAnalysisTaskService.Reservation.existing(task, "new-code-digest"));
        return (String) ReflectionTestUtils.getField(frozen, "directionPolicyVersion");
    }

    private JSONObject projection(StockWatchTechnicalAnalysisService service) {
        boolean deltaAwareProjection = Arrays.stream(service.getClass().getDeclaredMethods())
                .anyMatch(method -> "frozenProjectionJson".equals(method.getName())
                        && method.getParameterCount() == 20);
        String projectionJson = deltaAwareProjection
                ? ReflectionTestUtils.invokeMethod(service, "frozenProjectionJson",
                    session(), "宁德时代", LocalDate.of(2026, 8, 7), "14:30", "DAY", "2026-08-07",
                    "[]", "{}", "{}", "{}", 91L, 388.31,
                    "frozen-request-digest", "prompt-content-hash", 1200, "[]", "{}", "FULL", "{}", "snapshot-digest")
                : ReflectionTestUtils.invokeMethod(service, "frozenProjectionJson",
                    session(), "宁德时代", LocalDate.of(2026, 8, 7), "14:30", "DAY", "2026-08-07",
                    "[]", "{}", "{}", "{}", 91L, 388.31,
                    "frozen-request-digest", 1200, "[]", "{}");
        JSONObject projection = JSON.parseObject(projectionJson);
        projection.put("dataSnapshotDigest", ReflectionTestUtils.invokeMethod(service, "sha256", "{}"));
        return projection;
    }

    private StockWatchTechnicalSession session() {
        StockWatchTechnicalSession session = new StockWatchTechnicalSession();
        session.setId(81L);
        session.setSessionKey("300750-sz-day");
        session.setGeneration(2);
        session.setContextChars(1000);
        session.setContextLimitChars(80_000);
        return session;
    }

    private StockWatchTechnicalAnalysisService service(StockWatchTechnicalAnalysisMapper mapper) {
        return new StockWatchTechnicalAnalysisService(
                mock(EastmoneyMarketDataService.class), mock(StockWatchPredictionService.class),
                mock(StockWatchListService.class), mock(StockWatchTechnicalSessionMapper.class), mapper,
                mock(StockWatchAnalysisTaskService.class), mock(StockWatchAnalysisProjectionService.class),
                mock(StockWatchRiskPatternService.class), mock(StockWatchFactPersistenceService.class),
                mock(StockWatchFactObservationMapper.class), mock(StockWatchV6RuntimeGate.class),
                mock(StockWatchBridgeAdmissionService.class));
    }
}
