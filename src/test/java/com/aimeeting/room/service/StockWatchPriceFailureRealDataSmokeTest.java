package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchFactObservationMapper;
import com.aimeeting.room.dao.StockWatchPriceFailureAssessmentMapper;
import com.aimeeting.room.entity.StockWatchFactObservation;
import com.aimeeting.room.entity.StockWatchPriceFailureAssessment;
import com.aimeeting.room.service.StockWatchRiskPatternService.RiskPatternResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(properties = {
        "spring.main.web-application-type=none",
        "spring.sql.init.mode=never",
        "stock-watch.monitor.enabled=false",
        "stock-watch.technical-analysis.enabled=false",
        "stock-watch.delivery.enabled=false",
        "stock-watch.price-failure-research.enabled=false",
        "ai.tracking.refresh.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "RUN_STOCK_WATCH_PRICE_FAILURE_REAL_SMOKE", matches = "true")
@Transactional
class StockWatchPriceFailureRealDataSmokeTest {

    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");

    @Autowired StockWatchFactObservationMapper factMapper;
    @Autowired StockWatchPriceFailureAssessmentMapper assessmentMapper;
    @Autowired StockWatchPriceFailureResearchService researchService;
    @Autowired StockWatchRiskPatternService riskPatternService;
    @Autowired EastmoneyMarketDataService marketDataService;

    @Test
    void latestRealFactAndRealDailyBarsCanBeAssessedAndPersistedThenRolledBack() {
        long maximumId = factMapper.selectMaxId();
        List<StockWatchFactObservation> candidates = factMapper.selectAfterId(Math.max(0L, maximumId - 40), 40);
        candidates.sort(Comparator.comparing(StockWatchFactObservation::getId).reversed());
        Exception lastFailure = null;
        for (StockWatchFactObservation source : candidates) {
            if (!List.of("sh", "sz", "bj").contains(source.getMarket())) continue;
            try {
                StockWatchFactObservation enriched = enrichWithRealBars(source);
                long completedAt = enriched.getSourceTimeEpochMs() + 60_000L;
                StockWatchPriceFailureAssessment assessment = researchService.processObservationAt(enriched, completedAt);
                StockWatchPriceFailureAssessment persisted = assessmentMapper.selectByRuleObservation(
                        StockWatchPriceFailureResearchRule.RULE_VERSION, enriched.getId());
                assertNotNull(persisted);
                assertEquals(assessment.getId(), persisted.getId());
                JSONObject features = JSON.parseObject(persisted.getFeatureSnapshotJson());
                assertNotNull(features.get("return5Pct"));
                assertNotNull(features.get("priceVsMa20Pct"));
                assertNotNull(features.get("closePosition20dPct"));
                assertNotNull(features.get("volatility20Pct"));
                assertTrue(persisted.getAvailableTimeEpochMs() > persisted.getSourceTimeEpochMs());
                return;
            } catch (Exception failure) {
                lastFailure = failure;
            }
        }
        throw new AssertionError("no recent real fact could complete the point-in-time smoke", lastFailure);
    }

    private StockWatchFactObservation enrichWithRealBars(StockWatchFactObservation source) {
        JSONObject snapshot = JSON.parseObject(source.getFactSnapshotJson());
        JSONObject quote = snapshot.getJSONObject("quote");
        Map<String, Object> quoteMap = quote == null ? Collections.emptyMap() : quote.getInnerMap();
        List<Map<String, Object>> bars = marketDataService.fetchDailyBars(source.getSymbol(), source.getMarket(), 120);
        ZonedDateTime quoteAt = Instant.ofEpochMilli(source.getSourceTimeEpochMs()).atZone(CHINA);
        RiskPatternResult risk = riskPatternService.analyze(source.getSymbol(), source.getMarket(),
                quoteMap, bars, quoteAt.plusMinutes(1));
        snapshot.put("riskPatternObservations", risk.getObservations());
        snapshot.put("priceFailureResearchFeatures", risk.getPriceFailureResearchFeatures());
        StockWatchFactObservation enriched = new StockWatchFactObservation();
        enriched.setId(source.getId());
        enriched.setSymbol(source.getSymbol());
        enriched.setMarket(source.getMarket());
        enriched.setStockName(source.getStockName());
        enriched.setObservationKey(source.getObservationKey());
        enriched.setArrivalVersion(source.getArrivalVersion());
        enriched.setSourceTimeEpochMs(source.getSourceTimeEpochMs());
        enriched.setSourceDate(source.getSourceDate());
        enriched.setSourceTimeQuality(risk.getObservations().get("sourceQuality") == null
                ? source.getSourceTimeQuality()
                : String.valueOf(((Map<?, ?>) risk.getObservations().get("sourceQuality")).get("freshnessStatus")));
        enriched.setFactHash(source.getFactHash());
        enriched.setFactSnapshotJson(JSON.toJSONString(snapshot));
        return enriched;
    }
}
