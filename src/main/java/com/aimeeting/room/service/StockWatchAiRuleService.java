package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
public class StockWatchAiRuleService {

    @Value("${stock-watch.ai-rules.store-file:}")
    private String configuredStoreFile;

    @Value("${stock-watch.ai-rules.default-model:deepseek-chat}")
    private String defaultModel;

    private final List<AiWatchRule> rules = new ArrayList<>();
    private boolean loaded = false;

    public synchronized List<AiWatchRule> listAll() {
        ensureLoaded();
        return rules.stream().map(AiWatchRule::copy).collect(Collectors.toList());
    }

    public synchronized List<AiWatchRule> listEnabled() {
        ensureLoaded();
        return rules.stream()
                .filter(AiWatchRule::isEnabled)
                .map(AiWatchRule::copy)
                .collect(Collectors.toList());
    }

    public synchronized AiWatchRule create(Map<String, Object> payload) {
        ensureLoaded();
        AiWatchRule rule = normalize(fromPayload(payload, null));
        rule.setId(shortId());
        String now = Instant.now().toString();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);
        rules.add(rule);
        saveQuietly();
        return rule.copy();
    }

    public synchronized AiWatchRule update(String id, Map<String, Object> payload) {
        ensureLoaded();
        for (int i = 0; i < rules.size(); i++) {
            AiWatchRule existing = rules.get(i);
            if (existing.getId().equals(id)) {
                AiWatchRule updated = normalize(fromPayload(payload, existing.copy()));
                updated.setId(id);
                updated.setCreatedAt(existing.getCreatedAt());
                updated.setUpdatedAt(Instant.now().toString());
                rules.set(i, updated);
                saveQuietly();
                return updated.copy();
            }
        }
        return null;
    }

    public synchronized boolean delete(String id) {
        ensureLoaded();
        boolean removed = rules.removeIf(rule -> rule.getId().equals(id));
        if (removed) {
            saveQuietly();
        }
        return removed;
    }

    public synchronized AiWatchRule get(String id) {
        ensureLoaded();
        return rules.stream()
                .filter(rule -> rule.getId().equals(id))
                .findFirst()
                .map(AiWatchRule::copy)
                .orElse(null);
    }

    public List<AiWatchRule> parseTransientRules(Object rawRules) {
        List<AiWatchRule> parsed = new ArrayList<>();
        if (!(rawRules instanceof List)) {
            return parsed;
        }
        int index = 0;
        for (Object raw : (List<?>) rawRules) {
            if (!(raw instanceof Map)) {
                continue;
            }
            AiWatchRule rule = normalize(fromPayload((Map<String, Object>) raw, null));
            if (!StringUtils.hasText(rule.getId())) {
                rule.setId("inline_" + (++index));
            }
            parsed.add(rule);
        }
        return parsed;
    }

    private AiWatchRule fromPayload(Map<String, Object> payload, AiWatchRule base) {
        Map<String, Object> source = payload == null ? new LinkedHashMap<>() : payload;
        AiWatchRule rule = base == null ? new AiWatchRule() : base;
        if (source.containsKey("symbol")) rule.setSymbol(text(source.get("symbol"), rule.getSymbol()));
        if (source.containsKey("market")) rule.setMarket(text(source.get("market"), rule.getMarket()));
        if (source.containsKey("name")) rule.setName(text(source.get("name"), rule.getName()));
        if (source.containsKey("conditionText")) rule.setConditionText(text(source.get("conditionText"), rule.getConditionText()));
        if (source.containsKey("enabled")) rule.setEnabled(bool(source.get("enabled"), rule.isEnabled()));
        if (source.containsKey("notify")) rule.setNotify(bool(source.get("notify"), rule.isNotify()));
        if (source.containsKey("severity")) rule.setSeverity(text(source.get("severity"), rule.getSeverity()));
        if (source.containsKey("model")) rule.setModel(text(source.get("model"), rule.getModel()));
        if (source.containsKey("minConfidence")) rule.setMinConfidence(number(source.get("minConfidence"), rule.getMinConfidence()));
        if (source.containsKey("cooldownDays")) rule.setCooldownDays(integer(source.get("cooldownDays"), rule.getCooldownDays()));
        if (source.containsKey("note")) rule.setNote(text(source.get("note"), rule.getNote()));
        return rule;
    }

    private AiWatchRule normalize(AiWatchRule rule) {
        if (!StringUtils.hasText(rule.getSymbol())) {
            throw new IllegalArgumentException("股票编码不能为空");
        }
        StockSymbol normalized = StockSymbol.parse(rule.getSymbol(), rule.getMarket());
        rule.setSymbol(normalized.getSymbol());
        rule.setMarket(normalized.getMarket());
        if (!StringUtils.hasText(rule.getName())) {
            rule.setName(normalized.getSymbol());
        }
        if (!StringUtils.hasText(rule.getConditionText())) {
            throw new IllegalArgumentException("AI观察条件不能为空");
        }
        if (!StringUtils.hasText(rule.getSeverity())) {
            rule.setSeverity("risk");
        }
        if (!StringUtils.hasText(rule.getModel())) {
            rule.setModel(defaultModel);
        }
        if (rule.getMinConfidence() == null || rule.getMinConfidence() <= 0 || rule.getMinConfidence() > 1) {
            rule.setMinConfidence(0.75);
        }
        if (rule.getCooldownDays() == null || rule.getCooldownDays() < 0) {
            rule.setCooldownDays(1);
        }
        return rule;
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = storeFile();
        if (!Files.exists(path)) {
            return;
        }
        try {
            List<AiWatchRule> persisted = JSON.parseArray(
                    new String(Files.readAllBytes(path), StandardCharsets.UTF_8),
                    AiWatchRule.class
            );
            rules.clear();
            if (persisted != null) {
                for (AiWatchRule rule : persisted) {
                    try {
                        rules.add(normalize(rule));
                    } catch (Exception e) {
                        log.warn("skip invalid AI watch rule id={}, error={}", rule.getId(), e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("failed to load stock-watch AI rules: {}", e.getMessage());
        }
    }

    private void saveQuietly() {
        Path path = storeFile();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            Files.write(path, JSON.toJSONString(rules, true).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("保存AI观察条件失败: " + e.getMessage(), e);
        }
    }

    private Path storeFile() {
        if (StringUtils.hasText(configuredStoreFile)) {
            return Paths.get(expandHome(configuredStoreFile)).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("user.home"), ".aimeeting-room", "stock-watch-ai-rules.json");
    }

    private String expandHome(String path) {
        return path.startsWith("~/") ? System.getProperty("user.home") + path.substring(1) : path;
    }

    private String text(Object raw, String fallback) {
        if (raw == null) return fallback;
        String value = String.valueOf(raw).trim();
        return value.isEmpty() ? fallback : value;
    }

    private Boolean bool(Object raw, Boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean) return (Boolean) raw;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) return true;
        if ("false".equals(value) || "0".equals(value) || "no".equals(value)) return false;
        return fallback;
    }

    private Double number(Object raw, Double fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private Integer integer(Object raw, Integer fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number) return ((Number) raw).intValue();
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    @Data
    public static class AiWatchRule {
        private String id;
        private String symbol;
        private String market;
        private String name;
        private String conditionText;
        private boolean enabled = true;
        private boolean notify = true;
        private String severity = "risk";
        private String model;
        private Double minConfidence = 0.75;
        private Integer cooldownDays = 1;
        private String note;
        private String createdAt;
        private String updatedAt;

        public AiWatchRule copy() {
            AiWatchRule copy = new AiWatchRule();
            copy.id = id;
            copy.symbol = symbol;
            copy.market = market;
            copy.name = name;
            copy.conditionText = conditionText;
            copy.enabled = enabled;
            copy.notify = notify;
            copy.severity = severity;
            copy.model = model;
            copy.minConfidence = minConfidence;
            copy.cooldownDays = cooldownDays;
            copy.note = note;
            copy.createdAt = createdAt;
            copy.updatedAt = updatedAt;
            return copy;
        }
    }
}
