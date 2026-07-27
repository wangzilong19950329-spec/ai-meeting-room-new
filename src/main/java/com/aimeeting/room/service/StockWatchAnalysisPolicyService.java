package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchItemMapper;
import com.aimeeting.room.entity.StockWatchItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.ZonedDateTime;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class StockWatchAnalysisPolicyService {

    public static final String CAPABILITY = "stock.unified-analysis.v1";
    private static final List<String> MODES = List.of("INTERVAL", "DAILY_CLOSE", "ON_ANOMALY", "PAUSED");
    private static final List<String> ANOMALY_MODES = List.of(
            "IMMEDIATE_ANALYSIS", "DEFER_TO_NEXT_SCHEDULE", "ALERT_ONLY");

    private final StockWatchItemMapper itemMapper;
    private final StockWatchAnalysisSchedulePlanner planner;

    public void applyDefaults(StockWatchItem item) {
        if (item == null) return;
        item.setAnalysisMode(normalizeMode(item.getAnalysisMode()));
        item.setAnalysisIntervalMinutes("INTERVAL".equals(item.getAnalysisMode())
                ? normalizeInterval(item.getAnalysisIntervalMinutes()) : null);
        item.setAnomalyTriggerMode(normalizeAnomalyMode(item.getAnomalyTriggerMode()));
        item.setWeeklyReviewEnabled(item.getWeeklyReviewEnabled() == null || item.getWeeklyReviewEnabled());
        item.setMonthlyReviewEnabled(item.getMonthlyReviewEnabled() == null || item.getMonthlyReviewEnabled());
        item.setAnalysisPolicyVersion(item.getAnalysisPolicyVersion() == null ? 0L : item.getAnalysisPolicyVersion());
        item.setAnalysisPolicyModifiedAt(item.getAnalysisPolicyModifiedAt() == null
                ? new Date(0L) : item.getAnalysisPolicyModifiedAt());
        enforceCombinations(item);
    }

    public void decorate(StockWatchItem item, Map<String, Object> payload) {
        if (item == null || payload == null) return;
        applyDefaults(item);
        payload.put("analysisPolicy", policyPayload(item));
        payload.put("analysisSchedule", schedulePayload(item, ZonedDateTime.now(StockWatchAnalysisSchedulePlanner.CHINA_ZONE)));
    }

    public Map<String, Object> get(String rawSymbol, String rawMarket) {
        StockSymbol ref = StockSymbol.parse(rawSymbol, rawMarket);
        StockWatchItem item = itemMapper.selectBySymbolMarket(ref.getSymbol(), ref.getMarket());
        if (item == null) throw new IllegalArgumentException("监控股票不存在");
        applyDefaults(item);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", item.getSymbol());
        result.put("market", item.getMarket());
        result.put("analysisPolicy", policyPayload(item));
        result.put("analysisSchedule", schedulePayload(item, ZonedDateTime.now(StockWatchAnalysisSchedulePlanner.CHINA_ZONE)));
        return result;
    }

    @Transactional
    public Map<String, Object> update(String rawSymbol, String rawMarket, Map<String, Object> body) {
        StockSymbol ref = StockSymbol.parse(rawSymbol, rawMarket);
        StockWatchItem current = itemMapper.selectBySymbolMarket(ref.getSymbol(), ref.getMarket());
        if (current == null) throw new IllegalArgumentException("监控股票不存在");
        applyDefaults(current);
        Long expectedVersion = longValue(body == null ? null : body.get("expectedVersion"));
        if (expectedVersion == null) throw new IllegalArgumentException("expectedVersion不能为空");

        StockWatchItem update = new StockWatchItem();
        update.setId(current.getId());
        update.setAnalysisMode(normalizeRequiredMode(text(body, "mode", current.getAnalysisMode())));
        update.setAnalysisIntervalMinutes("INTERVAL".equals(update.getAnalysisMode())
                ? requiredInterval(body == null ? null : body.get("intervalMinutes"), current.getAnalysisIntervalMinutes())
                : null);
        update.setAnomalyTriggerMode(normalizeRequiredAnomalyMode(
                text(body, "anomalyTriggerMode", current.getAnomalyTriggerMode())));
        update.setWeeklyReviewEnabled(bool(body == null ? null : body.get("weeklyReviewEnabled"),
                Boolean.TRUE.equals(current.getWeeklyReviewEnabled())));
        update.setMonthlyReviewEnabled(bool(body == null ? null : body.get("monthlyReviewEnabled"),
                Boolean.TRUE.equals(current.getMonthlyReviewEnabled())));
        update.setAnalysisPolicyVersion(expectedVersion);
        enforceCombinations(update);
        if (itemMapper.updateAnalysisPolicy(update, expectedVersion) != 1) {
            throw new AnalysisPolicyConflictException("分析策略已被其他操作更新，请刷新后重试");
        }
        return get(ref.getSymbol(), ref.getMarket());
    }

    public StockWatchItem currentItem(String symbol, String market) {
        StockWatchItem item = itemMapper.selectBySymbolMarket(symbol, market);
        applyDefaults(item);
        return item;
    }

    public List<StockWatchItem> enabledItems() {
        List<StockWatchItem> items = itemMapper.selectEnabled();
        items.forEach(this::applyDefaults);
        return items;
    }

    public boolean allowsAutomaticDemand(StockWatchItem item, String triggerType) {
        if (item == null || !Boolean.TRUE.equals(item.getEnabled())) return false;
        applyDefaults(item);
        if ("PAUSED".equals(item.getAnalysisMode())) return false;
        if (triggerType != null && triggerType.startsWith("ANOMALY")) {
            return !"ALERT_ONLY".equals(item.getAnomalyTriggerMode());
        }
        if ("ON_ANOMALY".equals(item.getAnalysisMode())) return false;
        return true;
    }

    public Map<String, Object> policyPayload(StockWatchItem item) {
        applyDefaults(item);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("capability", CAPABILITY);
        payload.put("mode", item.getAnalysisMode());
        payload.put("intervalMinutes", item.getAnalysisIntervalMinutes());
        payload.put("anomalyTriggerMode", item.getAnomalyTriggerMode());
        payload.put("weeklyReviewEnabled", Boolean.TRUE.equals(item.getWeeklyReviewEnabled()));
        payload.put("monthlyReviewEnabled", Boolean.TRUE.equals(item.getMonthlyReviewEnabled()));
        payload.put("version", item.getAnalysisPolicyVersion());
        payload.put("modifiedAt", item.getAnalysisPolicyModifiedAt() == null
                ? null : item.getAnalysisPolicyModifiedAt().toInstant().toString());
        return payload;
    }

    private Map<String, Object> schedulePayload(StockWatchItem item, ZonedDateTime now) {
        StockWatchAnalysisSchedulePlanner.DueDecision decision = planner.currentDayDecision(item, now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("due", decision.isDue());
        payload.put("reason", decision.getReason());
        payload.put("currentSlotKey", decision.getScheduleSlotKey());
        payload.put("nextDueAt", decision.getNextDueAt() == null ? null : decision.getNextDueAt().toString());
        payload.put("requestedCadence", requestedCadence(item));
        payload.put("capacityStatus", "UNBOUNDED");
        return payload;
    }

    private String requestedCadence(StockWatchItem item) {
        if ("INTERVAL".equals(item.getAnalysisMode())) return "PT" + item.getAnalysisIntervalMinutes() + "M";
        return item.getAnalysisMode();
    }

    private void enforceCombinations(StockWatchItem item) {
        if ("ON_ANOMALY".equals(item.getAnalysisMode())) {
            item.setAnomalyTriggerMode("IMMEDIATE_ANALYSIS");
            item.setWeeklyReviewEnabled(false);
            item.setMonthlyReviewEnabled(false);
        }
        if ("PAUSED".equals(item.getAnalysisMode())) {
            item.setAnomalyTriggerMode("ALERT_ONLY");
            item.setWeeklyReviewEnabled(false);
            item.setMonthlyReviewEnabled(false);
        }
        if ("INTERVAL".equals(item.getAnalysisMode())) {
            item.setAnalysisIntervalMinutes(normalizeInterval(item.getAnalysisIntervalMinutes()));
        } else {
            item.setAnalysisIntervalMinutes(null);
        }
    }

    private int requiredInterval(Object raw, Integer fallback) {
        int value;
        try {
            value = raw == null ? normalizeInterval(fallback) : Integer.parseInt(String.valueOf(raw));
        } catch (Exception e) {
            throw new IllegalArgumentException("intervalMinutes必须是10到1440之间的整数");
        }
        if (value < 10 || value > 1440) throw new IllegalArgumentException("intervalMinutes必须在10到1440之间");
        return value;
    }

    private int normalizeInterval(Integer value) {
        return Math.max(10, Math.min(1440, value == null ? 30 : value));
    }

    private String normalizeMode(String raw) {
        String value = StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : "INTERVAL";
        return MODES.contains(value) ? value : "INTERVAL";
    }

    private String normalizeRequiredMode(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(value)) throw new IllegalArgumentException("不支持的分析模式：" + raw);
        return value;
    }

    private String normalizeAnomalyMode(String raw) {
        String value = StringUtils.hasText(raw) ? raw.trim().toUpperCase(Locale.ROOT) : "IMMEDIATE_ANALYSIS";
        return ANOMALY_MODES.contains(value) ? value : "IMMEDIATE_ANALYSIS";
    }

    private String normalizeRequiredAnomalyMode(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!ANOMALY_MODES.contains(value)) throw new IllegalArgumentException("不支持的异动升级策略：" + raw);
        return value;
    }

    private String text(Map<String, Object> body, String key, String fallback) {
        if (body == null || body.get(key) == null) return fallback;
        String value = String.valueOf(body.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    private boolean bool(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean) return (Boolean) raw;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (List.of("true", "1", "yes", "y").contains(value)) return true;
        if (List.of("false", "0", "no", "n").contains(value)) return false;
        return fallback;
    }

    private Long longValue(Object raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(String.valueOf(raw));
        } catch (Exception e) {
            throw new IllegalArgumentException("expectedVersion必须是整数");
        }
    }

    public static class AnalysisPolicyConflictException extends IllegalStateException {
        public AnalysisPolicyConflictException(String message) {
            super(message);
        }
    }
}
