package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.aimeeting.room.dao.StockWatchDeliveryConfigMapper;
import com.aimeeting.room.dao.StockWatchDeliveryLogMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.entity.StockWatchDeliveryConfig;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockWatchDeliveryServiceTrustedViewTest {

    @Test
    void previewPolicyAndEmailUseTrustedV6ViewWithoutRawFallback() {
        StockWatchDeliveryConfigMapper configMapper = mock(StockWatchDeliveryConfigMapper.class);
        StockWatchDeliveryLogMapper logMapper = mock(StockWatchDeliveryLogMapper.class);
        StockWatchTechnicalAnalysisMapper analysisMapper = mock(StockWatchTechnicalAnalysisMapper.class);
        StockWatchDeliveryService service = new StockWatchDeliveryService(
                configMapper, logMapper, analysisMapper, mock(NotificationService.class));

        StockWatchTechnicalAnalysis record = v6Record();
        StockWatchDeliveryConfig config = new StockWatchDeliveryConfig();
        config.setId(9L);
        config.setSymbol("002979");
        config.setMarket("sz");
        config.setStockName("雷赛智能");
        config.setRecipientsJson("[\"test@example.com\"]");
        config.setActiveFrom(new java.util.Date(Date.valueOf(LocalDate.of(2026, 7, 1)).getTime()));
        config.setTriggerPolicyJson("{\"sendOnNewAnalysis\":true,\"keywords\":[\"技术信号偏空\"]}");
        when(configMapper.selectBySymbolMarket("002979", "sz")).thenReturn(config);
        when(analysisMapper.selectSuccessfulSince("002979", "sz", config.getActiveFrom(), 1))
                .thenReturn(Collections.singletonList(record));
        when(logMapper.selectByAnalysisRecipient(org.mockito.ArgumentMatchers.eq(701L),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(null);

        Map<String, Object> preview = service.preview("002979.sz", "");
        Map<?, ?> item = (Map<?, ?>) ((java.util.List<?>) preview.get("preview")).get(0);

        assertEquals("当日技术信号偏空", item.get("title"));
        assertEquals("SERVER_TECHNICAL_V1", item.get("technicalEvidenceQuality"));
        assertFalse(item.toString().contains("模型原始结论"));

        StringBuilder email = new StringBuilder();
        ReflectionTestUtils.invokeMethod(service, "appendAnalysisBlock", email, 1, record);
        assertTrue(email.toString().contains("服务器技术信号"));
        assertTrue(email.toString().contains("主力证据画像（服务器确定性分类，非行动信号）"));
        assertFalse(email.toString().contains("模型原始结论"));
        assertFalse(email.toString().contains("主力意图识别（模型推断"));
    }

    @Test
    void damagedMainForceJsonCannotDisableTrustedV6EmailView() {
        StockWatchDeliveryService service = new StockWatchDeliveryService(
                mock(StockWatchDeliveryConfigMapper.class), mock(StockWatchDeliveryLogMapper.class),
                mock(StockWatchTechnicalAnalysisMapper.class), mock(NotificationService.class));
        StockWatchTechnicalAnalysis record = v6Record();
        record.setMainForceIntentJson("{damaged-main-force-json");

        StringBuilder email = new StringBuilder();
        ReflectionTestUtils.invokeMethod(service, "appendAnalysisBlock", email, 1, record);

        assertTrue(email.toString().contains("服务器技术信号"));
        assertTrue(email.toString().contains("当日技术信号偏空"));
        assertFalse(email.toString().contains("模型原始结论"));
        assertFalse(email.toString().contains("建议立即买入"));
    }

    private StockWatchTechnicalAnalysis v6Record() {
        StockWatchTechnicalAnalysis record = new StockWatchTechnicalAnalysis();
        record.setId(701L);
        record.setResponseContractVersion(6);
        record.setAnalysisDate(Date.valueOf("2026-07-17"));
        record.setRunSlot("11:30");
        record.setPeriodType("DAY");
        record.setStatus("SUCCESS");
        record.setTitle("主力意图证据不足（未满足可验证画像）");
        record.setConclusion("模型原始结论");
        record.setRawResponse("模型原始结论：建议立即买入");
        record.setMainForceIntentStatus("VALID_WITH_WARNINGS");
        record.setMainForceIntentJson("{\"contractVersion\":6,\"evidenceQuality\":\"SERVER_PROFILE_V1\","
                + "\"intent\":\"UNKNOWN\",\"timeWindow\":\"INTRADAY\",\"confidence\":0,"
                + "\"risk\":\"服务器确定性分类，不构成行动信号。\",\"watchPoints\":[]}");
        record.setEvidenceSignalJson(JSON.toJSONString(signals()));
        record.setEvidenceJson("[]");
        return record;
    }

    private Map<String, Object> signals() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schema", "stock_watch_evidence_signals_v2");
        root.put("atomCatalogVersion", 2);
        root.put("periodType", "DAY");
        Map<String, Object> price = new LinkedHashMap<>();
        price.put("changePercent", -1.64d);
        price.put("volumeRatio", 1.2d);
        price.put("turnoverPercent", 4.74d);
        price.put("activity", "MEDIUM");
        price.put("limitations", Collections.emptyList());
        root.put("priceVolumeSignal", price);
        Map<String, Object> position = new LinkedHashMap<>();
        position.put("closeYuan", 57.55d);
        position.put("ma20", 59.87d);
        position.put("macdDif", 1.75d);
        position.put("macdDea", 2.54d);
        position.put("macdHistogram", -1.59d);
        position.put("rsi14", 56.92d);
        position.put("return5Pct", -12.4d);
        position.put("return20Pct", 4.5d);
        position.put("limitations", Collections.emptyList());
        root.put("positionSignal", position);
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("anchorMainInflowYuan", 19_876_845d);
        flow.put("anchorMainInflowRatioPercent", 3.24d);
        flow.put("limitations", Collections.emptyList());
        root.put("fundFlowEvidence", flow);
        return root;
    }
}
