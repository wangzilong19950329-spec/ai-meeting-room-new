package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.aimeeting.room.dao.StockWatchFcCardMapper;
import com.aimeeting.room.entity.StockWatchFcCard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class StockWatchFcCardService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Pattern STOCK_SECTION_PATTERN = Pattern.compile("^##\\s+(.+?)(?:\\s+|[（(])(\\d{6})(?:[）)])?.*$");
    private static final Pattern CARD_HEADING_PATTERN = Pattern.compile("^###\\s+(.+)$");
    private static final List<String> REQUIRED_FIELDS = Arrays.asList(
            "thesis", "triggerLogic", "dataSources", "collectionMethod",
            "frequency", "parserSpec", "baselineReading", "failureMonitor");

    private final StockWatchFcCardMapper cardMapper;

    public Map<String, Object> list(String symbol, String market) {
        StockRef ref = parseStockRef(symbol, market);
        List<StockWatchFcCard> cards = cardMapper.selectBySymbolMarket(ref.symbol, ref.market);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("symbol", ref.symbol);
        result.put("market", ref.market);
        result.put("cards", cards.stream().map(this::toPayload).collect(Collectors.toList()));
        result.put("stats", buildStats(cards));
        return result;
    }

    public Map<String, Object> save(Map<String, Object> body) {
        StockRef ref = parseStockRef(text(body, "symbol", ""), text(body, "market", ""));
        String cardKey = firstNonBlank(text(body, "cardKey", ""), text(body, "key", ""));
        if (!StringUtils.hasText(cardKey)) {
            throw new IllegalArgumentException("FC卡编号不能为空");
        }

        StockWatchFcCard existing = cardMapper.selectBySymbolCardKey(ref.symbol, ref.market, cardKey);
        StockWatchFcCard card = existing == null ? new StockWatchFcCard() : existing;
        applyPayload(card, ref, body);
        recomputeActivation(card);
        if (existing == null) {
            cardMapper.insert(card);
        } else {
            cardMapper.update(card);
        }
        return toPayload(cardMapper.selectBySymbolCardKey(ref.symbol, ref.market, card.getCardKey()));
    }

    public Map<String, Object> update(Long id, Map<String, Object> body) {
        StockWatchFcCard card = cardMapper.selectById(id);
        if (card == null) {
            throw new IllegalArgumentException("FC卡不存在");
        }
        StockRef ref = new StockRef(card.getSymbol(), card.getMarket());
        applyPayload(card, ref, body);
        recomputeActivation(card);
        cardMapper.update(card);
        return toPayload(cardMapper.selectById(id));
    }

    public void delete(Long id) {
        cardMapper.deleteById(id);
    }

    public Map<String, Object> importMarkdown(Map<String, Object> body) {
        StockRef ref = parseStockRef(text(body, "symbol", ""), text(body, "market", ""));
        String stockName = text(body, "stockName", text(body, "name", ""));
        String markdown = text(body, "markdown", "");
        if (!StringUtils.hasText(markdown)) {
            throw new IllegalArgumentException("Markdown内容不能为空");
        }

        List<ParsedCard> parsedCards = parseMarkdown(markdown);
        List<StockWatchFcCard> matched = parsedCards.stream()
                .filter(card -> matchesCurrentStock(card, ref, stockName))
                .map(card -> toEntity(card, ref, stockName))
                .collect(Collectors.toList());

        if (matched.isEmpty()) {
            throw new IllegalArgumentException("未解析到属于当前股票的FC卡，请确认Markdown里包含股票代码或股票小节");
        }

        List<Map<String, Object>> saved = new ArrayList<>();
        for (StockWatchFcCard card : matched) {
            StockWatchFcCard existing = cardMapper.selectBySymbolCardKey(ref.symbol, ref.market, card.getCardKey());
            if (existing != null) {
                card.setId(existing.getId());
                cardMapper.update(card);
            } else {
                cardMapper.insert(card);
            }
            saved.add(toPayload(cardMapper.selectBySymbolCardKey(ref.symbol, ref.market, card.getCardKey())));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("imported", saved.size());
        result.put("cards", saved);
        result.put("ignored", Math.max(0, parsedCards.size() - saved.size()));
        result.put("message", "已导入当前股票FC卡 " + saved.size() + " 张，其他股票卡已忽略");
        return result;
    }

    private void applyPayload(StockWatchFcCard card, StockRef ref, Map<String, Object> body) {
        card.setSymbol(ref.symbol);
        card.setMarket(ref.market);
        card.setStockName(text(body, "stockName", card.getStockName()));
        card.setCardKey(firstNonBlank(text(body, "cardKey", card.getCardKey()), text(body, "key", card.getCardKey())));
        card.setCardTitle(text(body, "cardTitle", text(body, "title", firstNonBlank(card.getCardTitle(), card.getCardKey()))));
        card.setCardType(text(body, "cardType", text(body, "type", card.getCardType())));
        card.setThesis(text(body, "thesis", card.getThesis()));
        card.setTriggerLogic(text(body, "triggerLogic", card.getTriggerLogic()));
        card.setDataSourceTier(normalizeTier(text(body, "dataSourceTier", card.getDataSourceTier())));
        card.setDataSources(text(body, "dataSources", card.getDataSources()));
        card.setCollectionMethod(normalizeCollectionMethod(text(body, "collectionMethod", card.getCollectionMethod())));
        card.setFrequency(text(body, "frequency", card.getFrequency()));
        card.setParserSpec(text(body, "parserSpec", card.getParserSpec()));
        card.setBaselineReading(text(body, "baselineReading", card.getBaselineReading()));
        card.setFailureMonitor(text(body, "failureMonitor", card.getFailureMonitor()));
        card.setAutoProxy(text(body, "autoProxy", card.getAutoProxy()));
        card.setNotifyEnabled(bool(body.get("notifyEnabled"), card.getNotifyEnabled() == null || card.getNotifyEnabled()));
        card.setLastCollectStatus(text(body, "lastCollectStatus", card.getLastCollectStatus()));
        card.setLastCollectNote(text(body, "lastCollectNote", card.getLastCollectNote()));
        card.setRawMarkdown(text(body, "rawMarkdown", card.getRawMarkdown()));
    }

    private StockWatchFcCard toEntity(ParsedCard parsed, StockRef ref, String stockName) {
        StockWatchFcCard card = new StockWatchFcCard();
        card.setSymbol(ref.symbol);
        card.setMarket(ref.market);
        card.setStockName(firstNonBlank(stockName, parsed.stockName));
        card.setCardKey(firstNonBlank(parsed.cardKey, normalizeCardKey(parsed.heading)));
        card.setCardTitle(firstNonBlank(parsed.cardTitle, parsed.heading));
        card.setCardType(parsed.cardType);
        card.setThesis(parsed.fields.get("thesis"));
        card.setTriggerLogic(parsed.fields.get("triggerLogic"));
        card.setDataSourceTier(normalizeTier(firstNonBlank(parsed.fields.get("dataSourceTier"), parsed.body)));
        card.setDataSources(parsed.fields.get("dataSources"));
        card.setCollectionMethod(normalizeCollectionMethod(parsed.fields.get("collectionMethod")));
        card.setFrequency(parsed.fields.get("frequency"));
        card.setParserSpec(parsed.fields.get("parserSpec"));
        card.setBaselineReading(parsed.fields.get("baselineReading"));
        card.setFailureMonitor(parsed.fields.get("failureMonitor"));
        card.setAutoProxy(parsed.autoProxy);
        card.setNotifyEnabled(true);
        card.setRawMarkdown(parsed.rawMarkdown);
        recomputeActivation(card);
        return card;
    }

    private void recomputeActivation(StockWatchFcCard card) {
        List<String> blockers = new ArrayList<>();
        if (!StringUtils.hasText(card.getCardKey())) blockers.add("缺少卡号");
        if (!StringUtils.hasText(card.getCardTitle())) blockers.add("缺少标题");
        for (String field : REQUIRED_FIELDS) {
            if (!StringUtils.hasText(readField(card, field))) {
                blockers.add("缺少" + fieldLabel(field));
            }
        }
        if ("T4".equalsIgnoreCase(firstNonBlank(card.getDataSourceTier(), ""))) {
            blockers.add("T4来源不可执行");
        }
        String baseline = firstNonBlank(card.getBaselineReading(), "");
        if (!StringUtils.hasText(baseline) || baseline.contains("待采") || baseline.contains("未采") || baseline.contains("采不到")) {
            blockers.add("基线未采");
        }
        String collection = firstNonBlank(card.getCollectionMethod(), "");
        if ((collection.contains("自动") || collection.contains("半自动"))
                && (firstNonBlank(card.getLastCollectStatus(), "").isEmpty()
                || "PENDING".equalsIgnoreCase(card.getLastCollectStatus()))) {
            blockers.add("采集器未验收");
        }

        card.setActivationBlockers(JSON.toJSONString(blockers));
        if ("T4".equalsIgnoreCase(firstNonBlank(card.getDataSourceTier(), ""))) {
            card.setActivationStatus("INVALID");
            card.setEnabled(false);
        } else if (blockers.isEmpty()) {
            card.setActivationStatus("ACTIVE");
            card.setEnabled(true);
        } else {
            card.setActivationStatus("DRAFT");
            card.setEnabled(false);
        }
        if (!StringUtils.hasText(card.getLastCollectStatus())) {
            card.setLastCollectStatus("PENDING");
        }
    }

    private List<ParsedCard> parseMarkdown(String markdown) {
        String[] lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<ParsedCard> cards = new ArrayList<>();
        ParsedCard current = null;
        String currentStockName = "";
        String currentSymbol = "";
        StringBuilder currentRaw = new StringBuilder();

        for (String line : lines) {
            String trimmedLine = line.trim();
            Matcher stockMatcher = STOCK_SECTION_PATTERN.matcher(trimmedLine);
            if (stockMatcher.matches()) {
                if (current != null) {
                    current.rawMarkdown = currentRaw.toString().trim();
                    hydrateParsedCard(current);
                    cards.add(current);
                    current = null;
                    currentRaw = new StringBuilder();
                }
                currentStockName = stockMatcher.group(1).trim();
                currentSymbol = stockMatcher.group(2).trim();
                continue;
            }

            Matcher cardMatcher = CARD_HEADING_PATTERN.matcher(trimmedLine);
            if (cardMatcher.matches()) {
                if (current != null) {
                    current.rawMarkdown = currentRaw.toString().trim();
                    hydrateParsedCard(current);
                    cards.add(current);
                }
                current = new ParsedCard();
                current.heading = cardMatcher.group(1).trim();
                current.stockName = currentStockName;
                current.symbol = currentSymbol;
                currentRaw = new StringBuilder();
            }

            if (current != null) {
                currentRaw.append(line).append('\n');
            }
        }

        if (current != null) {
            current.rawMarkdown = currentRaw.toString().trim();
            hydrateParsedCard(current);
            cards.add(current);
        }
        return cards;
    }

    private void hydrateParsedCard(ParsedCard card) {
        card.body = firstNonBlank(card.rawMarkdown, "");
        parseHeading(card);
        for (String line : card.body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- **自动代理")) {
                card.autoProxy = cleanMarkdown(trimmed.replaceFirst("^-\\s*\\*\\*自动代理[^*]*\\*\\*[:：]?", ""));
                continue;
            }
            Matcher matcher = Pattern.compile("^\\d+\\.\\s*\\*\\*(.+?)\\*\\*\\s*[:：](.*)$").matcher(trimmed);
            if (!matcher.matches()) {
                continue;
            }
            String key = normalizeFieldKey(matcher.group(1));
            String value = cleanMarkdown(matcher.group(2));
            if ("baselineAndFailure".equals(key)) {
                splitBaselineAndFailure(card, value);
            } else if (StringUtils.hasText(key)) {
                card.fields.put(key, value);
            }
        }
        if (!StringUtils.hasText(card.fields.get("dataSourceTier"))) {
            card.fields.put("dataSourceTier", normalizeTier(card.fields.get("dataSources")));
        }
    }

    private void parseHeading(ParsedCard card) {
        String heading = card.heading;
        String[] parts = heading.split("\\|");
        card.cardKey = parts.length > 0 ? parts[0].trim() : normalizeCardKey(heading);
        card.cardType = parts.length > 1 ? parts[1].trim() : "";
        card.cardTitle = parts.length > 2 ? parts[2].trim() : heading;
    }

    private void splitBaselineAndFailure(ParsedCard card, String value) {
        String[] parts = value.split("\\|");
        card.fields.put("baselineReading", cleanMarkdown(parts.length > 0 ? parts[0] : value));
        if (parts.length > 1) {
            card.fields.put("failureMonitor", cleanMarkdown(parts[1].replaceFirst("^\\s*失效监控\\s*[:：]?", "")));
        }
    }

    private boolean matchesCurrentStock(ParsedCard card, StockRef ref, String stockName) {
        String body = firstNonBlank(card.rawMarkdown, "") + "\n" + firstNonBlank(card.stockName, "") + "\n" + firstNonBlank(card.heading, "");
        if (body.contains(ref.symbol)) {
            return true;
        }
        String name = firstNonBlank(stockName, "");
        if (StringUtils.hasText(name) && body.contains(name)) {
            return true;
        }
        if (StringUtils.hasText(name) && name.length() >= 2 && body.contains(name.substring(0, 2))) {
            return true;
        }
        return StringUtils.hasText(card.symbol) && card.symbol.equals(ref.symbol);
    }

    private Map<String, Object> toPayload(StockWatchFcCard card) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", card.getId());
        payload.put("symbol", card.getSymbol());
        payload.put("market", card.getMarket());
        payload.put("stockName", card.getStockName());
        payload.put("cardKey", card.getCardKey());
        payload.put("cardTitle", card.getCardTitle());
        payload.put("cardType", card.getCardType());
        payload.put("thesis", card.getThesis());
        payload.put("triggerLogic", card.getTriggerLogic());
        payload.put("dataSourceTier", card.getDataSourceTier());
        payload.put("dataSources", card.getDataSources());
        payload.put("collectionMethod", card.getCollectionMethod());
        payload.put("frequency", card.getFrequency());
        payload.put("parserSpec", card.getParserSpec());
        payload.put("baselineReading", card.getBaselineReading());
        payload.put("failureMonitor", card.getFailureMonitor());
        payload.put("autoProxy", card.getAutoProxy());
        payload.put("activationStatus", card.getActivationStatus());
        payload.put("activationBlockers", parseBlockers(card.getActivationBlockers()));
        payload.put("enabled", Boolean.TRUE.equals(card.getEnabled()));
        payload.put("notifyEnabled", card.getNotifyEnabled() == null || Boolean.TRUE.equals(card.getNotifyEnabled()));
        payload.put("lastCollectStatus", firstNonBlank(card.getLastCollectStatus(), "PENDING"));
        payload.put("lastCollectNote", card.getLastCollectNote());
        payload.put("lastCollectedAt", formatDateTime(card.getLastCollectedAt()));
        payload.put("rawMarkdown", card.getRawMarkdown());
        payload.put("gmtModify", formatDateTime(card.getGmtModify()));
        return payload;
    }

    private Map<String, Object> buildStats(List<StockWatchFcCard> cards) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("total", cards.size());
        stats.put("active", cards.stream().filter(card -> "ACTIVE".equalsIgnoreCase(card.getActivationStatus())).count());
        stats.put("draft", cards.stream().filter(card -> "DRAFT".equalsIgnoreCase(card.getActivationStatus())).count());
        stats.put("manual", cards.stream().filter(card -> firstNonBlank(card.getCollectionMethod(), "").contains("人工")).count());
        stats.put("auto", cards.stream().filter(card -> firstNonBlank(card.getCollectionMethod(), "").equals("自动")).count());
        stats.put("semiAuto", cards.stream().filter(card -> firstNonBlank(card.getCollectionMethod(), "").contains("半自动")).count());
        return stats;
    }

    private List<String> parseBlockers(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Collections.emptyList();
        }
        try {
            JSONArray array = JSON.parseArray(raw);
            return array.stream().map(String::valueOf).collect(Collectors.toList());
        } catch (Exception ignored) {
            return Arrays.stream(raw.split("[,;，、]"))
                    .map(String::trim)
                    .filter(StringUtils::hasText)
                    .collect(Collectors.toList());
        }
    }

    private String normalizeFieldKey(String raw) {
        String key = raw == null ? "" : raw.trim();
        if (key.contains("论点")) return "thesis";
        if (key.contains("触发逻辑")) return "triggerLogic";
        if (key.contains("数据源")) return "dataSources";
        if (key.contains("采集方式")) return "collectionMethod";
        if (key.contains("频率")) return "frequency";
        if (key.contains("解析方法")) return "parserSpec";
        if (key.contains("基线读数") || key.contains("失效监控")) return "baselineAndFailure";
        return "";
    }

    private String readField(StockWatchFcCard card, String field) {
        switch (field) {
            case "thesis": return card.getThesis();
            case "triggerLogic": return card.getTriggerLogic();
            case "dataSources": return card.getDataSources();
            case "collectionMethod": return card.getCollectionMethod();
            case "frequency": return card.getFrequency();
            case "parserSpec": return card.getParserSpec();
            case "baselineReading": return card.getBaselineReading();
            case "failureMonitor": return card.getFailureMonitor();
            default: return "";
        }
    }

    private String fieldLabel(String field) {
        switch (field) {
            case "thesis": return "论点";
            case "triggerLogic": return "触发逻辑";
            case "dataSources": return "数据源";
            case "collectionMethod": return "采集方式";
            case "frequency": return "频率";
            case "parserSpec": return "解析方法";
            case "baselineReading": return "基线读数";
            case "failureMonitor": return "失效监控";
            default: return field;
        }
    }

    private StockRef parseStockRef(String rawSymbol, String rawMarket) {
        String value = firstNonBlank(rawSymbol, "").trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        String market = firstNonBlank(rawMarket, "").trim().toLowerCase(Locale.ROOT);
        Matcher prefixed = Pattern.compile("^(sh|sz|bj)[._-]?(\\d{6})$").matcher(value);
        if (prefixed.matches()) {
            return new StockRef(prefixed.group(2), prefixed.group(1));
        }
        Matcher suffixed = Pattern.compile("^(\\d{6})(?:[._-]?(sh|sz|bj))?$").matcher(value);
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

    private boolean bool(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean) return (Boolean) raw;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value) || "y".equals(value)) return true;
        if ("false".equals(value) || "0".equals(value) || "no".equals(value) || "n".equals(value)) return false;
        return fallback;
    }

    private String text(Map<?, ?> map, String key, String fallback) {
        if (map == null) return fallback;
        return text(map.get(key), fallback);
    }

    private String text(Object raw, String fallback) {
        if (raw == null) return fallback;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? fallback : value;
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

    private String normalizeTier(String raw) {
        String value = firstNonBlank(raw, "").toUpperCase(Locale.ROOT);
        Matcher matcher = Pattern.compile("T[1-4](?:\\s*[-–—]\\s*T[1-4])?").matcher(value);
        if (matcher.find()) {
            return matcher.group().replaceAll("\\s+", "");
        }
        return "";
    }

    private String normalizeCollectionMethod(String raw) {
        String value = firstNonBlank(raw, "");
        if (value.contains("半自动")) return "半自动";
        if (value.contains("人工")) return "人工";
        if (value.contains("自动")) return "自动";
        return value;
    }

    private String normalizeCardKey(String raw) {
        String value = firstNonBlank(raw, "FC-CARD");
        return value.split("\\|")[0].trim().replaceAll("\\s+", "-");
    }

    private String cleanMarkdown(String raw) {
        return firstNonBlank(raw, "")
                .replace("**", "")
                .replace("`", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String formatDateTime(Date date) {
        if (date == null) return "";
        return DATE_TIME_FORMATTER.format(date.toInstant().atZone(CHINA_ZONE).toLocalDateTime());
    }

    private static class ParsedCard {
        private String symbol = "";
        private String stockName = "";
        private String heading = "";
        private String body = "";
        private String rawMarkdown = "";
        private String cardKey = "";
        private String cardTitle = "";
        private String cardType = "";
        private String autoProxy = "";
        private final Map<String, String> fields = new LinkedHashMap<>();
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
