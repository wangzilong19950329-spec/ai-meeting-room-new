package com.aimeeting.room.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class StockWatchN8nFacadeService {

    public static final String SCHEMA_VERSION = "stock-watch.n8n.quote.v1";
    static final String PROVIDER = "EASTMONEY";
    static final String VOLUME_UNIT = "LOT_100_SHARES";
    static final String AMOUNT_UNIT = "CNY";
    static final String UNIT_CONTRACT_STATUS = "EMPIRICAL_A_SHARE_ONLY";
    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MINUTE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final StockQuoteService stockQuoteService;
    private final Clock clock;
    private final long maxQuoteAgeSeconds;
    private final long futureToleranceSeconds;

    @Autowired
    public StockWatchN8nFacadeService(
            StockQuoteService stockQuoteService,
            @Value("${STOCK_WATCH_N8N_MAX_QUOTE_AGE_SECONDS:180}") long maxQuoteAgeSeconds,
            @Value("${STOCK_WATCH_N8N_FUTURE_TOLERANCE_SECONDS:5}") long futureToleranceSeconds) {
        this(stockQuoteService, Clock.systemUTC(), maxQuoteAgeSeconds, futureToleranceSeconds);
    }

    StockWatchN8nFacadeService(StockQuoteService stockQuoteService,
                               Clock clock,
                               long maxQuoteAgeSeconds,
                               long futureToleranceSeconds) {
        this.stockQuoteService = stockQuoteService;
        this.clock = clock;
        this.maxQuoteAgeSeconds = Math.max(1L, maxQuoteAgeSeconds);
        this.futureToleranceSeconds = Math.max(0L, futureToleranceSeconds);
    }

    public Map<String, Object> fetchQuotes(String symbols) {
        List<Map<String, Object>> sourceQuotes = stockQuoteService.fetchQuotes(symbols);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> quote : sourceQuotes) {
            Map<String, Object> bars = null;
            if (Boolean.TRUE.equals(quote.get("ok")) && supportedMarket(text(quote.get("market")))) {
                bars = stockQuoteService.fetchIntradayBars(text(quote.get("symbol")), text(quote.get("market")));
            }
            items.add(normalize(quote, bars, clock.instant()));
        }

        Instant fetchedAt = clock.instant();
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("provider", PROVIDER);
        envelope.put("fetchedAt", DateTimeFormatter.ISO_INSTANT.format(fetchedAt));
        envelope.put("requestedSymbols", symbols);
        envelope.put("items", items);
        envelope.put("itemCount", items.size());
        envelope.put("allUsable", !items.isEmpty() && items.stream().allMatch(this::isUsable));
        return envelope;
    }

    private Map<String, Object> normalize(Map<String, Object> quote,
                                          Map<String, Object> intradayBars,
                                          Instant fetchedAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("symbol", text(quote.get("symbol")));
        item.put("market", text(quote.get("market")));
        item.put("provider", PROVIDER);
        item.put("source", text(quote.get("source")));

        List<String> errors = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        if (!Boolean.TRUE.equals(quote.get("ok"))) {
            errors.add("QUOTE_SOURCE_ERROR");
            String reason = text(quote.get("reason"));
            if (!reason.isEmpty()) {
                limitations.add(reason);
            }
            item.put("quality", quality("SOURCE_ERROR", errors, limitations, null));
            return item;
        }

        Double price = number(quote.get("price"));
        Double open = number(quote.get("open"));
        Double high = number(quote.get("high"));
        Double low = number(quote.get("low"));
        Double previousClose = number(quote.get("previousClose"));
        Long providerUpdateEpochSeconds = integer(quote.get("timestamp"));
        Double cumulativeVolumeLots = number(quote.get("volume"));
        Double cumulativeAmountCny = number(quote.get("amount"));
        String market = text(quote.get("market"));

        if (price == null) errors.add("MISSING_PRICE");
        else if (price <= 0) errors.add("NON_POSITIVE_PRICE");
        if (!supportedMarket(market)) errors.add("UNSUPPORTED_MARKET_UNIT_CONTRACT");
        if (high != null && low != null && high < low) errors.add("DAY_HIGH_BELOW_DAY_LOW");
        if (price != null && high != null && price > high) errors.add("PRICE_ABOVE_DAY_HIGH");
        if (price != null && low != null && price < low) errors.add("PRICE_BELOW_DAY_LOW");
        if (cumulativeVolumeLots == null) limitations.add("MISSING_CUMULATIVE_VOLUME");
        if (cumulativeAmountCny == null) limitations.add("MISSING_CUMULATIVE_AMOUNT");
        if (supportedMarket(market)) limitations.add("EMPIRICAL_EASTMONEY_F5_F6_UNIT_CONTRACT");

        Long quoteAgeSeconds = null;
        String intradayStatus = intradayBars == null
                ? ""
                : text(intradayBars.get("status")).trim().toUpperCase(Locale.ROOT);
        boolean intradayUsable = "VALID".equals(intradayStatus);
        if (!intradayStatus.isEmpty() && !intradayUsable) {
            errors.add("INTRADAY_STATUS_INVALID");
            limitations.add("INTRADAY_STATUS_" + intradayStatus);
        }
        Instant marketDataTime = intradayUsable ? marketDataTime(intradayBars) : null;
        Instant providerUpdateTime = providerUpdateEpochSeconds == null || providerUpdateEpochSeconds <= 0
                ? null
                : Instant.ofEpochSecond(providerUpdateEpochSeconds);
        if (marketDataTime == null) {
            errors.add("MISSING_MARKET_DATA_TIME");
            if (!intradayStatus.isEmpty() && intradayUsable) {
                limitations.add("INTRADAY_STATUS_" + text(intradayBars.get("status")));
            }
        } else {
            quoteAgeSeconds = Duration.between(marketDataTime, fetchedAt).getSeconds();
            if (quoteAgeSeconds < -futureToleranceSeconds) errors.add("FUTURE_QUOTE_TIME");
            else if (quoteAgeSeconds > maxQuoteAgeSeconds) errors.add("STALE_QUOTE");
        }

        Map<String, Object> pricing = new LinkedHashMap<>();
        pricing.put("currency", "CNY");
        pricing.put("last", price);
        pricing.put("open", open);
        pricing.put("dayHigh", high);
        pricing.put("dayLow", low);
        pricing.put("previousClose", previousClose);
        pricing.put("semantics", "CURRENT_QUOTE_AND_TRADING_DAY_OHLC");

        Map<String, Object> cumulative = new LinkedHashMap<>();
        cumulative.put("volumeRaw", cumulativeVolumeLots);
        cumulative.put("volumeUnit", VOLUME_UNIT);
        cumulative.put("volumeShares", multiply(cumulativeVolumeLots, 100));
        cumulative.put("amountRaw", cumulativeAmountCny);
        cumulative.put("amountUnit", AMOUNT_UNIT);
        cumulative.put("unitContractStatus", UNIT_CONTRACT_STATUS);
        cumulative.put("semantics", "TRADING_DAY_CUMULATIVE_VENDOR_COUNTERS");
        cumulative.put("resetPolicy", "REJECT_NEGATIVE_DELTA_AND_REBASE_AFTER_RESET");

        String status = errors.isEmpty() ? (limitations.isEmpty() ? "VALID" : "PARTIAL")
                : (errors.size() == 1 && errors.contains("STALE_QUOTE") ? "STALE" : "INVALID");
        item.put("name", text(quote.get("name")));
        item.put("quoteTime", marketDataTime == null ? null : DateTimeFormatter.ISO_INSTANT.format(marketDataTime));
        item.put("providerUpdateTime", providerUpdateTime == null
                ? null
                : DateTimeFormatter.ISO_INSTANT.format(providerUpdateTime));
        Map<String, Object> timeSemantics = new LinkedHashMap<>();
        timeSemantics.put("quoteTime", "LATEST_EASTMONEY_1M_BAR_START_TIME");
        timeSemantics.put("providerUpdateTime", "EASTMONEY_F124_PROVIDER_UPDATE_TIME");
        item.put("timeSemantics", timeSemantics);
        item.put("fetchedAt", DateTimeFormatter.ISO_INSTANT.format(fetchedAt));
        item.put("pricing", pricing);
        item.put("cumulative", cumulative);
        item.put("quality", quality(status, errors, limitations, quoteAgeSeconds));
        return item;
    }

    private Instant marketDataTime(Map<String, Object> intradayBars) {
        if (intradayBars == null) return null;
        String asOfTime = text(intradayBars.get("asOfTime"));
        if (asOfTime.isEmpty()) return null;
        try {
            return LocalDateTime.parse(asOfTime, MINUTE_FORMATTER).atZone(CHINA_ZONE).toInstant();
        } catch (Exception ignored) {
            try {
                return Instant.parse(asOfTime);
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private boolean supportedMarket(String market) {
        return "sh".equalsIgnoreCase(market) || "sz".equalsIgnoreCase(market);
    }

    private boolean isUsable(Map<String, Object> item) {
        Object qualityValue = item.get("quality");
        if (!(qualityValue instanceof Map)) return false;
        Object status = ((Map<?, ?>) qualityValue).get("status");
        return "VALID".equals(status) || "PARTIAL".equals(status);
    }

    private Map<String, Object> quality(String status,
                                        List<String> errors,
                                        List<String> limitations,
                                        Long quoteAgeSeconds) {
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("status", status);
        quality.put("errors", errors);
        quality.put("limitations", limitations);
        quality.put("quoteAgeSeconds", quoteAgeSeconds);
        quality.put("maxQuoteAgeSeconds", maxQuoteAgeSeconds);
        return quality;
    }

    private Double number(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Long integer(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Double multiply(Double value, int multiplier) {
        if (value == null) return null;
        return BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(multiplier)).doubleValue();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
