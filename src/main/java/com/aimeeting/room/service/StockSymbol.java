package com.aimeeting.room.service;

import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Canonical stock symbol used by the stock-watch pipeline.
 *
 * <p>A-share symbols are stored as six digits with market sh/sz/bj. Hong Kong
 * symbols are stored as five digits with market hk. User-facing Hong Kong forms
 * such as H09988, HK09988, 09988.HK and bare 09988 all normalize to 09988.hk.</p>
 */
public final class StockSymbol {

    private static final Pattern MAINLAND_PREFIXED = Pattern.compile("^(sh|sz|bj|sse|szse)[._:-]?(\\d{6})$");
    private static final Pattern MAINLAND_SUFFIXED = Pattern.compile("^(\\d{6})(?:[._:-]?(sh|sz|bj|sse|szse))?$");
    private static final Pattern HK_PREFIXED = Pattern.compile("^(?:h|hk|hkg|hkex)[._:-]?(\\d{1,5})$");
    private static final Pattern HK_SUFFIXED = Pattern.compile("^(\\d{1,5})[._:-]?(?:h|hk|hkg|hkex)$");
    private static final Pattern HK_BARE = Pattern.compile("^\\d{1,5}$");

    private final String symbol;
    private final String market;

    private StockSymbol(String symbol, String market) {
        this.symbol = symbol;
        this.market = market;
    }

    public static StockSymbol parse(String rawSymbol, String rawMarket) {
        String value = clean(rawSymbol);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("股票编码不能为空");
        }

        Matcher hkPrefixed = HK_PREFIXED.matcher(value);
        if (hkPrefixed.matches()) {
            return new StockSymbol(padHongKongSymbol(hkPrefixed.group(1)), "hk");
        }
        Matcher hkSuffixed = HK_SUFFIXED.matcher(value);
        if (hkSuffixed.matches()) {
            return new StockSymbol(padHongKongSymbol(hkSuffixed.group(1)), "hk");
        }

        Matcher mainlandPrefixed = MAINLAND_PREFIXED.matcher(value);
        if (mainlandPrefixed.matches()) {
            return new StockSymbol(mainlandPrefixed.group(2), normalizeMarket(mainlandPrefixed.group(1)));
        }
        Matcher mainlandSuffixed = MAINLAND_SUFFIXED.matcher(value);
        if (mainlandSuffixed.matches()) {
            String explicitMarket = mainlandSuffixed.group(2);
            String market = StringUtils.hasText(explicitMarket)
                    ? normalizeMarket(explicitMarket)
                    : normalizeMarketOrBlank(rawMarket);
            if (!StringUtils.hasText(market)) {
                market = inferMarket(mainlandSuffixed.group(1));
            }
            if ("hk".equals(market)) {
                throw new IllegalArgumentException("港股编码应为 1-5 位数字，可使用 H/HK 前缀或 HK 后缀");
            }
            return new StockSymbol(mainlandSuffixed.group(1), market);
        }

        String requestedMarket = normalizeMarketOrBlank(rawMarket);
        if (HK_BARE.matcher(value).matches() || ("hk".equals(requestedMarket) && value.matches("\\d{1,5}"))) {
            return new StockSymbol(padHongKongSymbol(value), "hk");
        }
        throw new IllegalArgumentException("股票编码格式不正确");
    }

    public static StockSymbol tryParse(String rawSymbol, String rawMarket) {
        try {
            return parse(rawSymbol, rawMarket);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public static String normalizeMarket(String rawMarket) {
        String value = clean(rawMarket).replace(".", "");
        switch (value) {
            case "sh":
            case "sse":
                return "sh";
            case "sz":
            case "szse":
                return "sz";
            case "bj":
            case "bse":
                return "bj";
            case "h":
            case "hk":
            case "hkg":
            case "hkex":
                return "hk";
            default:
                throw new IllegalArgumentException("不支持的股票市场: " + rawMarket);
        }
    }

    public static String inferMarket(String symbol) {
        String value = clean(symbol);
        if (value.matches("\\d{5}")) return "hk";
        if (value.startsWith("6") || value.startsWith("9") || value.startsWith("5")) return "sh";
        if (value.startsWith("4") || value.startsWith("8")) return "bj";
        return "sz";
    }

    public static String eastmoneySecid(String symbol, String market) {
        String normalizedMarket = normalizeMarket(market);
        String marketId = "hk".equals(normalizedMarket) ? "116" : ("sh".equals(normalizedMarket) ? "1" : "0");
        return marketId + "." + symbol;
    }

    public static String marketFromEastmoney(Integer marketId, String symbol) {
        if (marketId != null && marketId == 116) return "hk";
        if (marketId != null && marketId == 1) return "sh";
        return inferMarket(symbol);
    }

    public String getSymbol() {
        return symbol;
    }

    public String getMarket() {
        return market;
    }

    public String eastmoneySecid() {
        return eastmoneySecid(symbol, market);
    }

    public String label() {
        return symbol + "." + market;
    }

    public boolean isHongKong() {
        return "hk".equals(market);
    }

    private static String normalizeMarketOrBlank(String rawMarket) {
        if (!StringUtils.hasText(rawMarket)) return "";
        return normalizeMarket(rawMarket);
    }

    private static String padHongKongSymbol(String symbol) {
        String value = clean(symbol);
        if (!value.matches("\\d{1,5}")) {
            throw new IllegalArgumentException("港股编码格式不正确");
        }
        return String.format(Locale.ROOT, "%05d", Integer.parseInt(value));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
}
