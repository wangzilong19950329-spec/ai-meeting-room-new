package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.aimeeting.room.dao.StockWatchFactHeadMapper;
import com.aimeeting.room.dao.StockWatchFactObservationMapper;
import com.aimeeting.room.dao.StockWatchIntentTestMapper;
import com.aimeeting.room.dao.StockWatchIntentTestResultMapper;
import com.aimeeting.room.entity.StockWatchFactHead;
import com.aimeeting.room.entity.StockWatchFactObservation;
import com.aimeeting.room.entity.StockWatchIntentTest;
import com.aimeeting.room.entity.StockWatchIntentTestResult;
import com.aimeeting.room.service.StockWatchRiskPatternService.RiskPatternResult;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StockWatchFactPersistenceService {

    private static final List<String> QUOTE_ALLOWLIST = Arrays.asList(
            "symbol", "market", "name", "source", "timestamp", "updateTime", "price", "open", "high", "low",
            "previousClose", "changePercent", "changeAmount", "volume", "amount", "turnover", "volumeRatio",
            "mainInflow", "mainInflowRatio", "superLargeInflow", "largeInflow", "mediumInflow", "smallInflow"
    );

    private final StockWatchFactHeadMapper headMapper;
    private final StockWatchFactObservationMapper observationMapper;
    private final StockWatchIntentTestMapper intentTestMapper;
    private final StockWatchIntentTestResultMapper testResultMapper;

    @Transactional
    public ObservationReceipt observe(String symbol,
                                      String market,
                                      String stockName,
                                      Map<String, Object> quote,
                                      RiskPatternResult riskPattern,
                                      ZonedDateTime sampledAt) {
        if (riskPattern == null || riskPattern.getQuoteObservedAt() == null) {
            return ObservationReceipt.skipped("canonical_quote_timestamp_missing");
        }
        String normalizedMarket = market == null ? "" : market.trim().toLowerCase(Locale.ROOT);
        long sourceTimeEpochMs = riskPattern.getQuoteObservedAt().toInstant().toEpochMilli();
        Map<String, Object> factSnapshot = canonicalFactSnapshot(symbol, normalizedMarket, quote, riskPattern);
        String factJson = JSON.toJSONString(factSnapshot);
        String factHash = sha256(factJson);
        String observationKey = sha256(symbol + "|" + normalizedMarket + "|" + sourceTimeEpochMs + "|" + factHash);
        StockWatchFactObservation existing = observationMapper.selectByObservationKey(observationKey);
        if (existing != null) {
            return ObservationReceipt.duplicate(existing);
        }

        StockWatchFactHead head = lockOrCreateHead(symbol, normalizedMarket);
        long arrivalVersion = value(head.getArrivalVersion(), 0L) + 1L;
        StockWatchFactObservation observation = new StockWatchFactObservation();
        observation.setSymbol(symbol);
        observation.setMarket(normalizedMarket);
        observation.setStockName(stockName);
        observation.setObservationKey(observationKey);
        observation.setArrivalVersion(arrivalVersion);
        observation.setSourceTimeEpochMs(sourceTimeEpochMs);
        observation.setSourceDate(riskPattern.getQuoteObservedAt().toLocalDate().toString());
        observation.setSourceTimeQuality(sourceTimeQuality(riskPattern));
        observation.setFactHash(factHash);
        observation.setFactSnapshotJson(factJson);
        observation.setDataQualityJson(JSON.toJSONString(riskPattern.getObservations().get("sourceQuality")));
        observation.setSampledAt(Date.from((sampledAt == null ? ZonedDateTime.now() : sampledAt).toInstant()));
        try {
            observationMapper.insert(observation);
        } catch (DuplicateKeyException duplicate) {
            StockWatchFactObservation raced = observationMapper.selectByObservationKey(observationKey);
            if (raced != null) return ObservationReceipt.duplicate(raced);
            throw duplicate;
        }

        boolean advances = head.getLatestObservationId() == null
                || head.getLastSourceTimeEpochMs() == null
                || sourceTimeEpochMs > head.getLastSourceTimeEpochMs()
                || (sourceTimeEpochMs == head.getLastSourceTimeEpochMs() && !factHash.equals(head.getLastFactHash()));
        int updated = advances
                ? headMapper.updateLatest(head.getId(), value(head.getRowVersion(), 0L), arrivalVersion,
                observation.getId(), sourceTimeEpochMs, observation.getSourceDate(), observation.getSourceTimeQuality(), factHash)
                : headMapper.updateArrivalVersion(head.getId(), value(head.getRowVersion(), 0L), arrivalVersion);
        if (updated != 1) throw new IllegalStateException("fact_head_concurrent_update");
        if (!advances) return ObservationReceipt.stale(observation);

        List<Map<String, Object>> verdicts = evaluateExistingTests(observation, riskPattern);
        int createdTests = createTests(observation, riskPattern);
        return ObservationReceipt.created(observation, verdicts, createdTests);
    }

    @Transactional
    public int registerModelIntentTests(Long originObservationId,
                                        Long analysisTaskId,
                                        String symbol,
                                        String market,
                                        Map<String, Object> validatedIntent) {
        if (originObservationId == null || analysisTaskId == null
                || validatedIntent == null || validatedIntent.isEmpty()) return 0;
        String intent = String.valueOf(validatedIntent.getOrDefault("intent", "UNKNOWN")).toUpperCase(Locale.ROOT);
        int maxAge = maxAgeForWindow(String.valueOf(validatedIntent.getOrDefault("timeWindow", "INTRADAY")));
        List<TestDefinition> definitions = modelIntentDefinitions(intent, maxAge);
        int created = 0;
        String claimRef = "analysisTask:" + analysisTaskId + ":mainForceIntent";
        for (TestDefinition definition : definitions) {
            StockWatchIntentTest test = new StockWatchIntentTest();
            test.setSymbol(symbol);
            test.setMarket(market == null ? "" : market.toLowerCase(Locale.ROOT));
            test.setOriginObservationId(originObservationId);
            test.setActivationAfterObservationId(originObservationId);
            test.setTestKey("analysisTask:" + analysisTaskId + ":" + intent + ":"
                    + definition.metricPath + ":invalidation");
            test.setClaimRef(claimRef);
            test.setMetricPath(definition.metricPath);
            test.setComparisonOperator(definition.operator);
            test.setTargetValueJson(JSON.toJSONString(definition.target));
            test.setMaxObservationAge(definition.maxAge);
            test.setRequiredObservedCount(definition.requiredObserved);
            test.setAgeCount(0);
            test.setObservedCount(0);
            test.setTestVerdict("UNRESOLVED");
            test.setRowVersion(0L);
            try {
                intentTestMapper.insert(test);
                created++;
            } catch (DuplicateKeyException ignored) {
                // Reusing a cached model response must not duplicate the active tests.
            }
        }
        return created;
    }

    public List<StockWatchIntentTest> historyTests(String symbol, String market) {
        List<StockWatchIntentTest> rows = intentTestMapper.selectBySymbolMarket(symbol, market);
        return rows == null ? Collections.emptyList() : rows;
    }

    private List<TestDefinition> modelIntentDefinitions(String intent, int maxAge) {
        List<TestDefinition> definitions = new ArrayList<>();
        if (Arrays.asList("ACCUMULATE", "PULL_UP", "SUPPORT").contains(intent)) {
            definitions.add(new TestDefinition("dailyReturnPct", "LE", -1.0, maxAge, maxAge));
            definitions.add(new TestDefinition("mainInflow", "LT", -100_000_000.0, maxAge, maxAge));
        } else if (Arrays.asList("DISTRIBUTE", "SUPPRESS").contains(intent)) {
            definitions.add(new TestDefinition("dailyReturnPct", "GE", 1.0, maxAge, maxAge));
            definitions.add(new TestDefinition("mainInflow", "GT", 100_000_000.0, maxAge, maxAge));
        } else if ("WASH".equals(intent)) {
            definitions.add(new TestDefinition("mainInflow", "LE", -100_000_000.0, maxAge, maxAge));
        }
        return definitions;
    }

    private int maxAgeForWindow(String rawWindow) {
        String window = rawWindow == null ? "" : rawWindow.toUpperCase(Locale.ROOT);
        if ("TWENTY_DAY".equals(window)) return 20;
        if ("FIVE_DAY".equals(window)) return 5;
        if ("THREE_DAY".equals(window)) return 3;
        return 3;
    }

    private StockWatchFactHead lockOrCreateHead(String symbol, String market) {
        StockWatchFactHead head = headMapper.selectForUpdate(symbol, market);
        if (head != null) return head;
        StockWatchFactHead created = new StockWatchFactHead();
        created.setSymbol(symbol);
        created.setMarket(market);
        created.setArrivalVersion(0L);
        created.setRowVersion(0L);
        try {
            headMapper.insert(created);
        } catch (DuplicateKeyException ignored) {
            // A concurrent transaction created the head first; lock that canonical row below.
        }
        head = headMapper.selectForUpdate(symbol, market);
        if (head == null) throw new IllegalStateException("fact_head_create_failed");
        return head;
    }

    private List<Map<String, Object>> evaluateExistingTests(StockWatchFactObservation observation,
                                                            RiskPatternResult riskPattern) {
        List<StockWatchIntentTest> tests = intentTestMapper.selectActiveForUpdate(
                observation.getSymbol(), observation.getMarket(), observation.getId());
        if (tests == null || tests.isEmpty()) return Collections.emptyList();
        Map<String, Object> derived = map(riskPattern.getObservations().get("derived"));
        List<Map<String, Object>> verdicts = new ArrayList<>();
        for (StockWatchIntentTest test : tests) {
            boolean newTradingDay = !observation.getSourceDate().equals(test.getLastEvaluatedSourceDate());
            int age = value(test.getAgeCount(), 0) + (newTradingDay ? 1 : 0);
            Double actual = number(derived.get(test.getMetricPath()));
            int observed = value(test.getObservedCount(), 0) + (newTradingDay && actual != null ? 1 : 0);
            Double target = number(JSON.parse(test.getTargetValueJson()));
            boolean matched = actual != null && target != null && compare(actual, target, test.getComparisonOperator());
            boolean mature = age >= value(test.getMaxObservationAge(), 1);
            boolean adequateCoverage = observed >= value(test.getRequiredObservedCount(), 1);
            boolean resolved = matched || mature;
            String verdict = matched ? "FALSIFIED"
                    : (mature ? (adequateCoverage ? "SUPPORTED" : "INSUFFICIENT_DATA") : "UNRESOLVED");

            StockWatchIntentTestResult result = new StockWatchIntentTestResult();
            result.setObservationId(observation.getId());
            result.setObservationVersion(observation.getArrivalVersion());
            result.setTestId(test.getId());
            result.setActualValueJson(JSON.toJSONString(actual));
            result.setMatchStatus(actual == null ? "MISSING" : (matched ? "MATCH" : "NO_MATCH"));
            result.setVerdictAfter(verdict);
            result.setAgeAfter(age);
            result.setObservedAfter(observed);
            result.setEvaluatedAt(new Date());
            testResultMapper.insert(result);
            int updated = intentTestMapper.updateProgress(test.getId(), value(test.getRowVersion(), 0L), age, observed,
                    verdict, observation.getId(), observation.getSourceDate(), resolved ? observation.getId() : null);
            if (updated != 1) throw new IllegalStateException("intent_test_concurrent_update:" + test.getId());

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("testId", test.getId());
            item.put("testKey", test.getTestKey());
            item.put("claimRef", test.getClaimRef());
            item.put("actual", actual);
            item.put("target", target);
            item.put("matchStatus", result.getMatchStatus());
            item.put("verdict", verdict);
            item.put("resolved", resolved);
            verdicts.add(item);
        }
        return verdicts;
    }

    private int createTests(StockWatchFactObservation observation, RiskPatternResult riskPattern) {
        Object rawPatterns = riskPattern.getObservations().get("patterns");
        if (!(rawPatterns instanceof List)) return 0;
        Map<String, Object> derived = map(riskPattern.getObservations().get("derived"));
        int created = 0;
        for (Object rawPattern : (List<?>) rawPatterns) {
            if (!(rawPattern instanceof Map)) continue;
            Map<?, ?> pattern = (Map<?, ?>) rawPattern;
            if (!"triggered".equals(pattern.get("status"))) continue;
            String patternId = String.valueOf(pattern.get("patternId"));
            TestDefinition definition = testDefinition(patternId, derived);
            if (definition == null || definition.target == null) continue;
            StockWatchIntentTest test = new StockWatchIntentTest();
            test.setSymbol(observation.getSymbol());
            test.setMarket(observation.getMarket());
            test.setOriginObservationId(observation.getId());
            test.setActivationAfterObservationId(observation.getId());
            test.setTestKey(patternId + ":" + definition.metricPath + ":invalidation");
            test.setClaimRef(patternId);
            test.setMetricPath(definition.metricPath);
            test.setComparisonOperator(definition.operator);
            test.setTargetValueJson(JSON.toJSONString(definition.target));
            test.setMaxObservationAge(definition.maxAge);
            test.setRequiredObservedCount(definition.requiredObserved);
            test.setAgeCount(0);
            test.setObservedCount(0);
            test.setTestVerdict("UNRESOLVED");
            test.setRowVersion(0L);
            try {
                intentTestMapper.insert(test);
                created++;
            } catch (DuplicateKeyException ignored) {
                // Origin observation plus test key is the idempotency boundary.
            }
        }
        return created;
    }

    private TestDefinition testDefinition(String patternId, Map<String, Object> derived) {
        if ("R01".equals(patternId)) return new TestDefinition("close", "GT", number(derived.get("breakoutHigh20")), 5, 2);
        if ("R02".equals(patternId)) return new TestDefinition("dailyReturnPct", "ABS_GT", 2.0, 5, 2);
        if ("R03".equals(patternId)) return new TestDefinition("closeLocationValue", "GE", 0.65, 3, 2);
        if ("R04".equals(patternId)) {
            Double close = number(derived.get("close"));
            return new TestDefinition("close", "GT", close == null ? null : close * 1.02, 5, 2);
        }
        return null;
    }

    private Map<String, Object> canonicalFactSnapshot(String symbol,
                                                      String market,
                                                      Map<String, Object> quote,
                                                      RiskPatternResult riskPattern) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schema", "stock-watch-fact-v1");
        snapshot.put("symbol", symbol);
        snapshot.put("market", market);
        Map<String, Object> canonicalQuote = new LinkedHashMap<>();
        if (quote != null) {
            for (String key : QUOTE_ALLOWLIST) {
                if (quote.containsKey(key)) canonicalQuote.put(key, quote.get(key));
            }
        }
        snapshot.put("quote", canonicalQuote);
        snapshot.put("riskPatternObservations", riskPattern.getObservations());
        return snapshot;
    }

    private String sourceTimeQuality(RiskPatternResult riskPattern) {
        Map<String, Object> sourceQuality = map(riskPattern.getObservations().get("sourceQuality"));
        Object value = sourceQuality.get("freshnessStatus");
        return value == null ? "UNKNOWN" : String.valueOf(value);
    }

    private boolean compare(double actual, double target, String operator) {
        if ("GT".equals(operator)) return actual > target;
        if ("GE".equals(operator)) return actual >= target;
        if ("LT".equals(operator)) return actual < target;
        if ("LE".equals(operator)) return actual <= target;
        if ("ABS_GT".equals(operator)) return Math.abs(actual) > target;
        return false;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : Collections.emptyMap();
    }

    private Double number(Object raw) {
        if (raw == null) return null;
        try {
            double value = raw instanceof Number ? ((Number) raw).doubleValue() : Double.parseDouble(String.valueOf(raw));
            return Double.isFinite(value) ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private int value(Integer raw, int fallback) {
        return raw == null ? fallback : raw;
    }

    private long value(Long raw, long fallback) {
        return raw == null ? fallback : raw;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : hash) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256_unavailable", e);
        }
    }

    private static class TestDefinition {
        private final String metricPath;
        private final String operator;
        private final Double target;
        private final int maxAge;
        private final int requiredObserved;

        private TestDefinition(String metricPath, String operator, Double target, int maxAge, int requiredObserved) {
            this.metricPath = metricPath;
            this.operator = operator;
            this.target = target;
            this.maxAge = maxAge;
            this.requiredObserved = requiredObserved;
        }
    }

    public static class ObservationReceipt {
        private final String status;
        private final String reason;
        private final StockWatchFactObservation observation;
        private final List<Map<String, Object>> testVerdicts;
        private final int createdTestCount;

        private ObservationReceipt(String status,
                                   String reason,
                                   StockWatchFactObservation observation,
                                   List<Map<String, Object>> testVerdicts,
                                   int createdTestCount) {
            this.status = status;
            this.reason = reason;
            this.observation = observation;
            this.testVerdicts = testVerdicts;
            this.createdTestCount = createdTestCount;
        }

        static ObservationReceipt skipped(String reason) {
            return new ObservationReceipt("SKIPPED", reason, null, Collections.emptyList(), 0);
        }

        static ObservationReceipt duplicate(StockWatchFactObservation observation) {
            return new ObservationReceipt("DUPLICATE", "observation_key_exists", observation, Collections.emptyList(), 0);
        }

        static ObservationReceipt stale(StockWatchFactObservation observation) {
            return new ObservationReceipt("STALE", "source_time_did_not_advance", observation, Collections.emptyList(), 0);
        }

        static ObservationReceipt created(StockWatchFactObservation observation,
                                          List<Map<String, Object>> testVerdicts,
                                          int createdTestCount) {
            return new ObservationReceipt("CREATED", null, observation, testVerdicts, createdTestCount);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", status);
            result.put("reason", reason);
            result.put("observationId", observation == null ? null : observation.getId());
            result.put("observationKey", observation == null ? null : observation.getObservationKey());
            result.put("arrivalVersion", observation == null ? null : observation.getArrivalVersion());
            result.put("sourceTimeEpochMs", observation == null ? null : observation.getSourceTimeEpochMs());
            result.put("testVerdicts", testVerdicts);
            result.put("createdTestCount", createdTestCount);
            return result;
        }
    }
}
