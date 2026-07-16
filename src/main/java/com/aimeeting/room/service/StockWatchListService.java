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
        StockSymbol ref = StockSymbol.parse(text(body, "symbol", text(body, "code", "")),
                text(body, "market", ""));
        StockWatchItem existing = itemMapper.selectBySymbolMarket(ref.getSymbol(), ref.getMarket());
        StockWatchItem item = existing == null ? new StockWatchItem() : existing;
        item.setSymbol(ref.getSymbol());
        item.setMarket(ref.getMarket());
        item.setStockName(resolveName(ref, text(body, "stockName", text(body, "name", ""))));
        item.setImageUrl(normalizeImageUrl(text(body, "imageUrl", text(body, "image", ""))));
        item.setEnabled(bool(body.get("enabled"), true));
        item.setSource(text(body, "source", existing == null ? "manual" : firstNonBlank(existing.getSource(), "manual")));
        item.setGroupKey(normalizeGroupKey(text(body, "groupKey", text(body, "group", "")), existing));
        if (existing == null) {
            itemMapper.insert(item);
        } else {
            itemMapper.update(item);
        }
        return toPayload(itemMapper.selectBySymbolMarket(ref.getSymbol(), ref.getMarket()));
    }

    private String resolveName(StockSymbol ref, String requestedName) {
        String name = firstNonBlank(requestedName, "");
        if (StringUtils.hasText(name) && !name.equalsIgnoreCase(ref.label())) {
            return name;
        }
        try {
            List<Map<String, Object>> quotes = stockQuoteService.fetchQuotes(ref.label());
            if (!quotes.isEmpty()) {
                String quoteName = text(quotes.get(0), "name", "");
                if (StringUtils.hasText(quoteName)) {
                    return quoteName;
                }
            }
        } catch (Exception ignored) {
            // Name enrichment is best-effort; the symbol is still enough for scheduling.
        }
        return ref.getSymbol() + "." + ref.getMarket().toUpperCase(Locale.ROOT);
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
        payload.put("groupKey", normalizeGroupKey(item.getGroupKey(), null));
        payload.put("groupName", groupName(item.getGroupKey()));
        return payload;
    }

    private String normalizeGroupKey(String rawGroupKey, StockWatchItem existing) {
        String fallback = existing == null ? "watch" : firstNonBlank(existing.getGroupKey(), "watch");
        String value = firstNonBlank(rawGroupKey, fallback).toLowerCase(Locale.ROOT);
        switch (value) {
            case "holding":
            case "position":
            case "portfolio":
            case "hold":
            case "持仓":
            case "持仓股":
                return "holding";
            case "candidate":
            case "candidate_stock":
            case "pool":
            case "备选":
            case "备选股":
                return "candidate";
            case "archived":
            case "archive":
            case "paused":
            case "归档":
            case "暂停":
                return "archived";
            case "watch":
            case "observe":
            case "observation":
            case "观察":
            case "观察股":
                return "watch";
            default:
                return "watch";
        }
    }

    private String groupName(String groupKey) {
        switch (normalizeGroupKey(groupKey, null)) {
            case "holding":
                return "持仓股";
            case "candidate":
                return "备选股";
            case "archived":
                return "归档";
            case "watch":
            default:
                return "观察股";
        }
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

}
