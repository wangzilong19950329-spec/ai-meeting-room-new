package com.aimeeting.room.service;

import com.aimeeting.room.dao.StockWatchItemMapper;
import com.aimeeting.room.entity.StockWatchItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockWatchListService {

    private final StockWatchItemMapper itemMapper;
    private final StockQuoteService stockQuoteService;

    public List<Map<String, Object>> listEnabled() {
        return itemMapper.selectEnabled().stream()
                .map(this::toPayload)
                .collect(Collectors.toList());
    }

    public List<String> listEnabledSymbols() {
        return itemMapper.selectEnabled().stream()
                .map(item -> item.getSymbol() + "." + item.getMarket())
                .distinct()
                .collect(Collectors.toList());
    }

    public Map<String, Object> save(Map<String, Object> body) {
        StockRef ref = normalizeStockRef(text(body, "symbol", text(body, "code", "")),
                text(body, "market", ""));
        StockWatchItem existing = itemMapper.selectBySymbolMarket(ref.symbol, ref.market);
        StockWatchItem item = existing == null ? new StockWatchItem() : existing;
        item.setSymbol(ref.symbol);
        item.setMarket(ref.market);
        item.setStockName(resolveName(ref, text(body, "stockName", text(body, "name", ""))));
        item.setImageUrl(normalizeImageUrl(text(body, "imageUrl", text(body, "image", ""))));
        item.setEnabled(bool(body.get("enabled"), true));
        item.setSource(text(body, "source", existing == null ? "manual" : firstNonBlank(existing.getSource(), "manual")));
        if (existing == null) {
            itemMapper.insert(item);
        } else {
            itemMapper.update(item);
        }
        return toPayload(itemMapper.selectBySymbolMarket(ref.symbol, ref.market));
    }

    private String resolveName(StockRef ref, String requestedName) {
        String name = firstNonBlank(requestedName, "");
        if (StringUtils.hasText(name) && !name.equalsIgnoreCase(ref.symbol + "." + ref.market)) {
            return name;
        }
        try {
            List<Map<String, Object>> quotes = stockQuoteService.fetchQuotes(ref.symbol + "." + ref.market);
            if (!quotes.isEmpty()) {
                String quoteName = text(quotes.get(0), "name", "");
                if (StringUtils.hasText(quoteName)) {
                    return quoteName;
                }
            }
        } catch (Exception ignored) {
            // Name enrichment is best-effort; the symbol is still enough for scheduling.
        }
        return ref.symbol + "." + ref.market.toUpperCase(Locale.ROOT);
    }

    private Map<String, Object> toPayload(StockWatchItem item) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (item == null) {
            return payload;
        }
        payload.put("id", item.getId());
        payload.put("symbol", item.getSymbol());
        payload.put("market", item.getMarket());
        payload.put("name", item.getStockName());
        payload.put("stockName", item.getStockName());
        payload.put("image", item.getImageUrl());
        payload.put("imageUrl", item.getImageUrl());
        payload.put("enabled", Boolean.TRUE.equals(item.getEnabled()));
        payload.put("source", firstNonBlank(item.getSource(), "manual"));
        return payload;
    }

    private StockRef normalizeStockRef(String rawSymbol, String rawMarket) {
        String value = firstNonBlank(rawSymbol, "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String market = firstNonBlank(rawMarket, "").trim().toLowerCase(Locale.ROOT);
        java.util.regex.Matcher prefixed = java.util.regex.Pattern
                .compile("^(sh|sz|bj)[._-]?(\\d{6})$")
                .matcher(value);
        if (prefixed.matches()) {
            return new StockRef(prefixed.group(2), prefixed.group(1));
        }
        java.util.regex.Matcher suffixed = java.util.regex.Pattern
                .compile("^(\\d{6})(?:[._-]?(sh|sz|bj))?$")
                .matcher(value);
        if (!suffixed.matches()) {
            throw new IllegalArgumentException("股票编码格式不正确");
        }
        String symbol = suffixed.group(1);
        String effectiveMarket = StringUtils.hasText(market)
                ? market
                : (StringUtils.hasText(suffixed.group(2)) ? suffixed.group(2) : inferMarket(symbol));
        return new StockRef(symbol, effectiveMarket);
    }

    private String inferMarket(String symbol) {
        if (symbol.startsWith("6") || symbol.startsWith("9")) return "sh";
        if (symbol.startsWith("4") || symbol.startsWith("8")) return "bj";
        return "sz";
    }

    private String normalizeImageUrl(String imageUrl) {
        String value = firstNonBlank(imageUrl, "");
        if (!StringUtils.hasText(value) || value.startsWith("data:")) {
            return "";
        }
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }

    private String text(Map<?, ?> map, String key, String fallback) {
        if (map == null) return fallback;
        Object value = map.get(key);
        return text(value, fallback);
    }

    private String text(Object raw, String fallback) {
        if (raw == null) return fallback;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? fallback : value;
    }

    private boolean bool(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean) return (Boolean) raw;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value) || "y".equals(value)) return true;
        if ("false".equals(value) || "0".equals(value) || "no".equals(value) || "n".equals(value)) return false;
        return fallback;
    }

    private String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private static class StockRef {
        private final String symbol;
        private final String market;

        private StockRef(String symbol, String market) {
            this.symbol = symbol;
            this.market = market;
        }
    }
}
