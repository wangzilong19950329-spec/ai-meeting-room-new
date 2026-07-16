package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchPredictionMapper;
import com.aimeeting.room.entity.StockWatchPrediction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockWatchPredictionService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final StockWatchPredictionMapper predictionMapper;
    private final EastmoneyMarketDataService marketDataService;

    @Value("${stock-watch.prediction.evaluation-batch-limit:200}")
    private int evaluationBatchLimit = 200;

    public List<StockWatchPrediction> savePredictions(Long analysisId,
                                                       String symbol,
                                                       String market,
                                                       String stockName,
                                                       LocalDate forecastDate,
                                                       Double sourcePrice,
                                                       JSONArray forecasts,
                                                       String inputSnapshotJson) {
        return persistPredictions(analysisId, symbol, market, stockName, forecastDate, sourcePrice,
                forecasts, inputSnapshotJson, false);
    }

    public List<StockWatchPrediction> savePredictionsStrict(Long analysisId,
                                                             String symbol,
                                                             String market,
                                                             String stockName,
                                                             LocalDate forecastDate,
                                                             Double sourcePrice,
                                                             JSONArray forecasts,
                                                             String inputSnapshotJson) {
        return persistPredictions(analysisId, symbol, market, stockName, forecastDate, sourcePrice,
                forecasts, inputSnapshotJson, true);
    }

    public void validatePredictionsStrict(Double sourcePrice, JSONArray forecasts) {
        if (sourcePrice == null || sourcePrice <= 0 || forecasts == null || forecasts.isEmpty()) {
            throw new IllegalArgumentException("prediction_projection_input_invalid");
        }
        Set<String> horizons = new HashSet<>();
        for (int i = 0; i < forecasts.size(); i++) {
            JSONObject item = forecasts.getJSONObject(i);
            if (item == null) {
                throw new IllegalArgumentException("prediction_projection_item_invalid:" + i);
            }
            String horizon = normalizeHorizon(item.getString("horizon"));
            String direction = normalizeDirection(item.getString("direction"));
            Double targetLow = decimal(item.get("targetLow"));
            Double targetHigh = decimal(item.get("targetHigh"));
            if (!StringUtils.hasText(horizon) || !StringUtils.hasText(direction)
                    || targetLow == null || targetHigh == null || targetLow <= 0 || targetLow > targetHigh) {
                throw new IllegalArgumentException("prediction_projection_item_invalid:" + i);
            }
            if (!horizons.add(horizon)) {
                throw new IllegalArgumentException("prediction_projection_horizon_duplicate:" + horizon);
            }
        }
    }

    private List<StockWatchPrediction> persistPredictions(Long analysisId,
                                                           String symbol,
                                                           String market,
                                                           String stockName,
                                                           LocalDate forecastDate,
                                                           Double sourcePrice,
                                                           JSONArray forecasts,
                                                           String inputSnapshotJson,
                                                           boolean strict) {
        if (strict) {
            validatePredictionsStrict(sourcePrice, forecasts);
        }
        if (analysisId == null || sourcePrice == null || sourcePrice <= 0 || forecasts == null) {
            if (strict) throw new IllegalArgumentException("prediction_projection_input_invalid");
            return Collections.emptyList();
        }
        List<StockWatchPrediction> saved = new ArrayList<>();
        for (int i = 0; i < forecasts.size(); i++) {
            JSONObject item = forecasts.getJSONObject(i);
            if (item == null) continue;
            String horizon = normalizeHorizon(item.getString("horizon"));
            String direction = normalizeDirection(item.getString("direction"));
            Double targetLow = decimal(item.get("targetLow"));
            Double targetHigh = decimal(item.get("targetHigh"));
            if (!StringUtils.hasText(horizon) || !StringUtils.hasText(direction)
                    || targetLow == null || targetHigh == null || targetLow <= 0 || targetLow > targetHigh) {
                if (strict) throw new IllegalArgumentException("prediction_projection_item_invalid:" + i);
                log.warn("skip invalid prediction analysisId={} index={}", analysisId, i);
                continue;
            }
            StockWatchPrediction prediction = new StockWatchPrediction();
            prediction.setAnalysisId(analysisId);
            prediction.setSymbol(symbol);
            prediction.setMarket(market);
            prediction.setStockName(stockName);
            prediction.setHorizonCode(horizon);
            prediction.setHorizonTradingDays(horizonTradingDays(horizon));
            prediction.setForecastDate(Date.valueOf(forecastDate));
            prediction.setSourcePrice(sourcePrice);
            prediction.setDirection(direction);
            prediction.setProbability(clamp(decimal(item.get("probability")), 0.0, 1.0));
            prediction.setTargetLow(targetLow);
            prediction.setTargetHigh(targetHigh);
            prediction.setExpectedReturnLow(calculatedReturn(targetLow, sourcePrice));
            prediction.setExpectedReturnHigh(calculatedReturn(targetHigh, sourcePrice));
            prediction.setInvalidationPrice(decimal(item.get("invalidationPrice")));
            prediction.setRationale(text(item.get("rationale")));
            prediction.setInputSnapshotJson(inputSnapshotJson);
            prediction.setStatus("PENDING");
            try {
                predictionMapper.insert(prediction);
                saved.add(prediction);
            } catch (Exception e) {
                if (strict) throw new IllegalStateException("prediction_projection_persist_failed:" + horizon, e);
                log.warn("failed to persist prediction analysisId={} horizon={} error={}", analysisId, horizon, e.getMessage());
            }
        }
        return saved;
    }

    public Map<String, Object> history(String symbol, String market, int limit) {
        List<StockWatchPrediction> records = predictionMapper.selectRecent(symbol, market, Math.max(10, Math.min(limit, 500)));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", symbol);
        payload.put("market", market);
        payload.put("summary", summarize(records));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StockWatchPrediction prediction : records) rows.add(toPayload(prediction));
        payload.put("records", rows);
        return payload;
    }

    @Scheduled(fixedDelayString = "${stock-watch.prediction.evaluation-interval-ms:1800000}",
            initialDelayString = "${stock-watch.prediction.evaluation-initial-delay-ms:180000}")
    public void evaluatePending() {
        int limit = effectiveEvaluationBatchLimit();
        List<StockWatchPrediction> pending = predictionMapper.selectPending(limit);
        if (pending.isEmpty()) return;
        long startedAt = System.currentTimeMillis();
        int evaluatedCount = 0;
        int deferredCount = 0;
        int sourceFailureCount = 0;
        int sourceFailureSkipCount = 0;
        Map<String, List<Map<String, Object>>> barsCache = new HashMap<>();
        Set<String> failedBarKeys = new HashSet<>();
        log.info("stock-watch prediction evaluation start pending={} limit={}", pending.size(), limit);
        for (StockWatchPrediction prediction : pending) {
            String cacheKey = predictionCacheKey(prediction);
            if (failedBarKeys.contains(cacheKey)) {
                deferredCount++;
                sourceFailureSkipCount++;
                log.debug("prediction evaluation deferred id={} sourceKey={} reason=source_failed_in_current_batch",
                        prediction.getId(), cacheKey);
                continue;
            }
            List<Map<String, Object>> bars = barsCache.get(cacheKey);
            if (!barsCache.containsKey(cacheKey)) {
                try {
                    List<Map<String, Object>> fetchedBars =
                            marketDataService.fetchDailyBars(prediction.getSymbol(), prediction.getMarket(), 800);
                    bars = fetchedBars == null ? Collections.emptyList() : fetchedBars;
                    barsCache.put(cacheKey, bars);
                } catch (Exception e) {
                    failedBarKeys.add(cacheKey);
                    sourceFailureCount++;
                    deferredCount++;
                    log.warn("prediction evaluation deferred id={} sourceKey={} error={}",
                            prediction.getId(), cacheKey, e.getMessage());
                    continue;
                }
            }
            try {
                if (evaluateOne(prediction, bars)) {
                    evaluatedCount++;
                }
            } catch (Exception e) {
                deferredCount++;
                log.warn("prediction evaluation deferred id={} error={}", prediction.getId(), e.getMessage());
            }
        }
        log.info("stock-watch prediction evaluation end pending={} limit={} evaluatedCount={} deferredCount={} sourceFailureKeyCount={} sourceFailureCount={} sourceFailureSkipCount={} elapsedMs={}",
                pending.size(), limit, evaluatedCount, deferredCount, failedBarKeys.size(), sourceFailureCount,
                sourceFailureSkipCount, System.currentTimeMillis() - startedAt);
    }

    private boolean evaluateOne(StockWatchPrediction prediction, List<Map<String, Object>> bars) {
        LocalDate forecastDate = toLocalDate(prediction.getForecastDate());
        List<Map<String, Object>> future = new ArrayList<>();
        for (Map<String, Object> bar : bars) {
            LocalDate date = parseDate(bar.get("date"));
            if (date != null && date.isAfter(forecastDate)) future.add(bar);
        }
        int days = prediction.getHorizonTradingDays() == null ? 1 : prediction.getHorizonTradingDays();
        if (future.size() < days) return false;

        List<Map<String, Object>> window = future.subList(0, days);
        Map<String, Object> finalBar = window.get(window.size() - 1);
        Double actualClose = decimal(finalBar.get("close"));
        if (actualClose == null || prediction.getSourcePrice() == null || prediction.getSourcePrice() <= 0) return false;
        double actualHigh = window.stream().map(bar -> decimal(bar.get("high"))).filter(value -> value != null)
                .mapToDouble(Double::doubleValue).max().orElse(actualClose);
        double actualLow = window.stream().map(bar -> decimal(bar.get("low"))).filter(value -> value != null)
                .mapToDouble(Double::doubleValue).min().orElse(actualClose);
        double actualReturn = (actualClose / prediction.getSourcePrice() - 1) * 100;
        String actualDirection = actualReturn >= 1 ? "UP" : (actualReturn <= -1 ? "DOWN" : "SIDEWAYS");
        boolean directionHit = actualDirection.equals(prediction.getDirection());
        boolean rangeCloseHit = between(actualClose, prediction.getTargetLow(), prediction.getTargetHigh());
        boolean rangeTouchHit = prediction.getTargetLow() != null && prediction.getTargetHigh() != null
                && actualHigh >= prediction.getTargetLow() && actualLow <= prediction.getTargetHigh();
        boolean invalidated = isInvalidated(prediction, actualLow, actualHigh);
        double midpoint = prediction.getTargetLow() == null || prediction.getTargetHigh() == null
                ? prediction.getSourcePrice()
                : (prediction.getTargetLow() + prediction.getTargetHigh()) / 2.0;
        double absoluteError = Math.abs(actualClose - midpoint) / prediction.getSourcePrice() * 100;
        double probability = prediction.getProbability() == null ? 0.5 : prediction.getProbability();
        double brier = Math.pow(probability - (directionHit ? 1 : 0), 2);
        LocalDate evaluatedDate = parseDate(finalBar.get("date"));
        String note = "实际方向=" + actualDirection + "，预测方向=" + prediction.getDirection()
                + "，区间收盘命中=" + rangeCloseHit + "，区间触达=" + rangeTouchHit;

        predictionMapper.updateEvaluation(prediction.getId(), "EVALUATED", Date.valueOf(evaluatedDate),
                round(actualClose), round(actualHigh), round(actualLow), round(actualReturn), directionHit,
                rangeCloseHit, rangeTouchHit, invalidated, round(absoluteError), round(brier), note, new java.util.Date());
        return true;
    }

    private int effectiveEvaluationBatchLimit() {
        return Math.max(1, Math.min(evaluationBatchLimit, 500));
    }

    private String predictionCacheKey(StockWatchPrediction prediction) {
        return normalizeCachePart(prediction.getSymbol()) + "." + normalizeCachePart(prediction.getMarket());
    }

    private String normalizeCachePart(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : "";
    }

    private Map<String, Object> summarize(List<StockWatchPrediction> records) {
        int pending = 0;
        int evaluated = 0;
        int directionHits = 0;
        int rangeHits = 0;
        double brierSum = 0;
        Map<String, int[]> byHorizon = new LinkedHashMap<>();
        for (StockWatchPrediction record : records) {
            if (!"EVALUATED".equals(record.getStatus())) {
                pending++;
                continue;
            }
            evaluated++;
            if (Boolean.TRUE.equals(record.getDirectionHit())) directionHits++;
            if (Boolean.TRUE.equals(record.getRangeCloseHit())) rangeHits++;
            if (record.getBrierScore() != null) brierSum += record.getBrierScore();
            int[] stats = byHorizon.computeIfAbsent(record.getHorizonCode(), key -> new int[2]);
            stats[0]++;
            if (Boolean.TRUE.equals(record.getDirectionHit())) stats[1]++;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", records.size());
        result.put("pending", pending);
        result.put("evaluated", evaluated);
        result.put("directionHitRate", evaluated == 0 ? null : round(directionHits * 100.0 / evaluated));
        result.put("rangeCloseHitRate", evaluated == 0 ? null : round(rangeHits * 100.0 / evaluated));
        result.put("averageBrierScore", evaluated == 0 ? null : round(brierSum / evaluated));
        Map<String, Object> horizonPayload = new LinkedHashMap<>();
        byHorizon.forEach((key, value) -> {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("evaluated", value[0]);
            stats.put("directionHitRate", value[0] == 0 ? null : round(value[1] * 100.0 / value[0]));
            horizonPayload.put(key, stats);
        });
        result.put("byHorizon", horizonPayload);
        return result;
    }

    private Map<String, Object> toPayload(StockWatchPrediction prediction) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", prediction.getId());
        row.put("analysisId", prediction.getAnalysisId());
        row.put("forecastDate", toLocalDate(prediction.getForecastDate()).toString());
        row.put("generatedAt", formatDateTime(prediction.getGmtCreate()));
        row.put("horizon", prediction.getHorizonCode());
        row.put("tradingDays", prediction.getHorizonTradingDays());
        row.put("sourcePrice", prediction.getSourcePrice());
        row.put("direction", prediction.getDirection());
        row.put("probability", prediction.getProbability());
        row.put("targetLow", prediction.getTargetLow());
        row.put("targetHigh", prediction.getTargetHigh());
        row.put("expectedReturnLow", prediction.getExpectedReturnLow());
        row.put("expectedReturnHigh", prediction.getExpectedReturnHigh());
        row.put("invalidationPrice", prediction.getInvalidationPrice());
        row.put("rationale", prediction.getRationale());
        row.put("status", prediction.getStatus());
        row.put("evaluatedTradeDate", prediction.getEvaluatedTradeDate() == null ? null : toLocalDate(prediction.getEvaluatedTradeDate()).toString());
        row.put("evaluatedAt", formatDateTime(prediction.getEvaluatedAt()));
        row.put("actualClose", prediction.getActualClose());
        row.put("actualReturnPct", prediction.getActualReturnPct());
        row.put("directionHit", prediction.getDirectionHit());
        row.put("rangeCloseHit", prediction.getRangeCloseHit());
        row.put("rangeTouchHit", prediction.getRangeTouchHit());
        row.put("invalidated", prediction.getInvalidated());
        row.put("absoluteErrorPct", prediction.getAbsoluteErrorPct());
        row.put("brierScore", prediction.getBrierScore());
        row.put("evaluationNote", prediction.getEvaluationNote());
        return row;
    }

    private boolean isInvalidated(StockWatchPrediction prediction, double actualLow, double actualHigh) {
        Double invalidation = prediction.getInvalidationPrice();
        if (invalidation == null) return false;
        if ("UP".equals(prediction.getDirection())) return actualLow <= invalidation;
        if ("DOWN".equals(prediction.getDirection())) return actualHigh >= invalidation;
        return invalidation >= prediction.getSourcePrice() ? actualHigh >= invalidation : actualLow <= invalidation;
    }

    private boolean between(Double value, Double low, Double high) {
        return value != null && low != null && high != null && value >= low && value <= high;
    }

    private String normalizeHorizon(String value) {
        if (!StringUtils.hasText(value)) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace("+", "");
        if ("1".equals(normalized) || "T1".equals(normalized)) return "T1";
        if ("5".equals(normalized) || "T5".equals(normalized)) return "T5";
        if ("W1".equals(normalized) || "WEEK".equals(normalized)) return "W1";
        if ("M1".equals(normalized) || "MONTH".equals(normalized)) return "M1";
        return "";
    }

    private int horizonTradingDays(String horizon) {
        if ("T5".equals(horizon) || "W1".equals(horizon)) return 5;
        if ("M1".equals(horizon)) return 20;
        return 1;
    }

    private String normalizeDirection(String value) {
        if (!StringUtils.hasText(value)) return "";
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "UP".equals(normalized) || "DOWN".equals(normalized) || "SIDEWAYS".equals(normalized) ? normalized : "";
    }

    private Double calculatedReturn(Double target, Double source) {
        return round((target / source - 1) * 100);
    }

    private Double clamp(Double value, double low, double high) {
        if (value == null) return 0.5;
        return Math.max(low, Math.min(high, value));
    }

    private Double decimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) return null;
        try {
            return value instanceof Number ? ((Number) value).doubleValue() : Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private LocalDate parseDate(Object value) {
        if (value == null) return null;
        try {
            return LocalDate.parse(String.valueOf(value).substring(0, 10));
        } catch (Exception ignored) {
            return null;
        }
    }

    private LocalDate toLocalDate(java.util.Date value) {
        if (value instanceof Date) return ((Date) value).toLocalDate();
        return value.toInstant().atZone(CHINA_ZONE).toLocalDate();
    }

    private String formatDateTime(java.util.Date value) {
        if (value == null) return "";
        return value.toInstant().atZone(CHINA_ZONE).toLocalDateTime().format(DATE_TIME_FORMATTER);
    }

    private double round(double value) {
        return Math.round(value * 1000000.0) / 1000000.0;
    }
}
