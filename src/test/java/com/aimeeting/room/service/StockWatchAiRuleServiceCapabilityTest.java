package com.aimeeting.room.service;

import com.aimeeting.room.service.StockWatchAiRuleService.AiWatchRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockWatchAiRuleServiceCapabilityTest {

    @TempDir Path tempDir;

    @Test
    void missingCapabilityMapsToExplicitLegacyCompatibility() {
        StockWatchAiRuleService service = service();
        AiWatchRule rule = service.create(baseRule());

        assertTrue(rule.isGeneralLegacy());
        assertEquals(Collections.singletonList(StockWatchAiRuleService.CAPABILITY_GENERAL_LEGACY), rule.getRequiredCapabilities());
        assertEquals(StockWatchAiRuleService.TRIGGER_MODE_LEGACY_MODEL_RULE, rule.getRiskPatternTriggerMode());
    }

    @Test
    void requiredRiskPatternCapabilityRequiresMatchingMode() {
        StockWatchAiRuleService service = service();
        Map<String, Object> payload = baseRule();
        payload.put("requiredCapabilities", Collections.singletonList(StockWatchAiRuleService.CAPABILITY_RISK_PATTERN_REQUIRED));
        payload.put("riskPatternTriggerMode", StockWatchAiRuleService.TRIGGER_MODE_REQUIRED);

        AiWatchRule rule = service.create(payload);

        assertTrue(rule.requiresRiskPattern());
        assertEquals(StockWatchAiRuleService.TRIGGER_MODE_REQUIRED, rule.getRiskPatternTriggerMode());
    }

    @Test
    void conflictingCapabilityFailsBeforeRuleCanRun() {
        StockWatchAiRuleService service = service();
        Map<String, Object> payload = baseRule();
        payload.put("requiredCapabilities", Collections.singletonList(StockWatchAiRuleService.CAPABILITY_L0_ADVISORY));
        payload.put("riskPatternTriggerMode", StockWatchAiRuleService.TRIGGER_MODE_REQUIRED);

        assertThrows(IllegalArgumentException.class, () -> service.create(payload));
    }

    private StockWatchAiRuleService service() {
        StockWatchAiRuleService service = new StockWatchAiRuleService();
        ReflectionTestUtils.setField(service, "configuredStoreFile", tempDir.resolve("rules.json").toString());
        ReflectionTestUtils.setField(service, "defaultModel", "deepseek-web");
        return service;
    }

    private Map<String, Object> baseRule() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", "002979.sz");
        payload.put("name", "雷赛智能");
        payload.put("conditionText", "观察主力资金与价格结构");
        return payload;
    }
}
