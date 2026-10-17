package com.aimeeting.room.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies every Arabic-numeral number in a reviewed speech by the evidence that contains it.
 * Low-confidence numbers stay publishable but are labelled; numbers absent from all evidence
 * are reported as unsupported so the caller can ask for one rewrite and then reject the turn.
 */
final class ReviewedNumberGate {

    static final String LOW_CONFIDENCE_LABEL = "〔低置信〕";

    enum Support { VERIFIED, LOW_CONFIDENCE, UNSUPPORTED }

    private static final Pattern NUMBER = Pattern.compile(
            "(?:(?<![\\d.%％)）])([-+−]))?(?<![\\d.])(\\d+(?:,\\d{3})*(?:\\.\\d+)?)"
                    + "\\s*(万亿|亿|万|千)?\\s*"
                    + "(个百分点|美元|港元|元|%|％|倍|bps|bp|手|股|户|次|家|吨|台|辆|gwh|mwh|kwh)?",
            Pattern.CASE_INSENSITIVE);

    private static final List<Pattern> EXEMPT = List.of(
            // Dates and clock times
            Pattern.compile("(?:19|20)\\d{2}\\s*[-/.]\\s*\\d{1,2}(?:\\s*[-/.]\\s*\\d{1,2})?"),
            Pattern.compile("(?:19|20)\\d{2}\\s*年(?:\\s*\\d{1,2}\\s*月(?:\\s*\\d{1,2}\\s*[日号])?)?"),
            Pattern.compile("\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]"),
            Pattern.compile("\\d{1,2}:\\d{2}(?::\\d{2})?"),
            Pattern.compile("(?i)(?<![a-z])[qh][1-4](?!\\d)"),
            // Rounds, ordinals and list markers
            Pattern.compile("第\\s*\\d+"),
            Pattern.compile("(?m)^\\s*\\d{1,2}\\s*[、)）]"),
            Pattern.compile("(?m)^\\s*\\d{1,2}\\.(?!\\d)"),
            Pattern.compile("[（(]\\s*\\d{1,2}\\s*[)）]"),
            // Periods and durations
            Pattern.compile("\\d+\\s*个?\\s*(?:交易日|工作日|自然日|月|周|星期|天|日|年|小时|分钟|季度)"),
            // Stock codes
            Pattern.compile("(?i)(?<![a-z0-9])(?:sh|sz|bj)[._-]?\\d{6}(?!\\d)"),
            Pattern.compile("(?i)(?<!\\d)\\d{6}[._-]?(?:sh|sz|bj)(?![a-z])"),
            // Technical indicator parameters such as MA20 or MACD(12,26,9)
            Pattern.compile("(?i)(?<![a-z])(?:ma|ema|sma|rsi|kdj|boll|macd|wr|cci|atr|dmi|bias|roc|obv|vr|psy|dif|dea)"
                    + "\\s*[(（]?\\s*\\d+(?:\\s*[,，、]\\s*\\d+)*\\s*[)）]?"));

    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private ReviewedNumberGate() {
    }

    static Audit audit(String speech, String verifiedCorpus, String provisionalCorpus, String stockSymbol) {
        List<Token> verified = tokens(stripUrls(verifiedCorpus), stockSymbol);
        List<Token> provisional = tokens(stripUrls(provisionalCorpus), stockSymbol);
        List<Item> items = new ArrayList<>();
        for (Token token : tokens(speech, stockSymbol)) {
            Support support = anySupports(verified, token) ? Support.VERIFIED
                    : anySupports(provisional, token) ? Support.LOW_CONFIDENCE
                    : Support.UNSUPPORTED;
            items.add(new Item(token, support));
        }
        return new Audit(items);
    }

    static String rewriteInstruction(Audit audit) {
        StringBuilder listed = new StringBuilder();
        for (Item item : audit.unsupported()) {
            if (listed.length() > 0) listed.append("、");
            listed.append(item.token.text);
        }
        return "【数字来源校验未通过】你上一版发言中的这些数字在本轮资料包里找不到来源：" + listed + "。"
                + "请重写整段发言：只能使用资料包中出现过的数字；找不到来源的数字请删除或改为不含数字的定性表述；"
                + "论点计数请用中文数字。仍按 JSON 论据、==REPLY==、发言正文的格式输出。";
    }

    private static boolean anySupports(List<Token> evidence, Token claim) {
        for (Token candidate : evidence) {
            if (supports(candidate, claim)) return true;
        }
        return false;
    }

    private static boolean supports(Token evidence, Token claim) {
        if (evidence.unit != null && claim.unit != null && !evidence.unit.equals(claim.unit)) return false;
        if (evidence.value.compareTo(claim.value) == 0) return true;
        if (evidence.value.signum() != claim.value.signum()) return false;
        // Allow ordinary display rounding in the speaker's own multiplier, never a change of scale.
        BigDecimal inClaimScale = evidence.value.divide(claim.multiplier);
        return inClaimScale.setScale(claim.displayed.scale(), RoundingMode.HALF_UP)
                .compareTo(claim.displayed) == 0;
    }

    private static List<Token> tokens(String text, String stockSymbol) {
        if (text == null || text.isBlank()) return Collections.emptyList();
        boolean[] exempt = exemptMask(text, stockSymbol);
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            int digitsStart = matcher.start(2);
            if (exempt[digitsStart]) continue;
            BigDecimal displayed;
            try {
                displayed = new BigDecimal(matcher.group(2).replace(",", ""));
            } catch (NumberFormatException invalid) {
                continue;
            }
            if (matcher.group(1) != null) displayed = displayed.negate();
            BigDecimal multiplier = multiplier(matcher.group(3));
            String unit = canonicalUnit(matcher.group(4));
            String visible = text.substring(matcher.start(), matcher.end()).trim();
            tokens.add(new Token(visible, matcher.start(), matcher.start() + visible.length(),
                    displayed, multiplier, displayed.multiply(multiplier), unit));
        }
        return tokens;
    }

    private static boolean[] exemptMask(String text, String stockSymbol) {
        boolean[] mask = new boolean[text.length() + 1];
        List<Pattern> patterns = new ArrayList<>(EXEMPT);
        if (stockSymbol != null && stockSymbol.matches("\\d{6}")) {
            patterns.add(Pattern.compile("(?<!\\d)" + stockSymbol + "(?!\\d)"));
        }
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                for (int i = matcher.start(); i < matcher.end(); i++) mask[i] = true;
            }
        }
        return mask;
    }

    private static BigDecimal multiplier(String word) {
        if (word == null) return BigDecimal.ONE;
        switch (word) {
            case "万亿": return new BigDecimal("1000000000000");
            case "亿": return new BigDecimal("100000000");
            case "万": return new BigDecimal("10000");
            case "千": return new BigDecimal("1000");
            default: return BigDecimal.ONE;
        }
    }

    private static String canonicalUnit(String unit) {
        if (unit == null) return null;
        String lower = unit.toLowerCase(Locale.ROOT);
        if ("％".equals(lower)) return "%";
        if ("bps".equals(lower)) return "bp";
        return lower;
    }

    private static String stripUrls(String text) {
        return text == null ? "" : URL.matcher(text).replaceAll(" ");
    }

    static final class Audit {
        private final List<Item> items;

        private Audit(List<Item> items) {
            this.items = items;
        }

        boolean hasUnsupported() {
            return !unsupported().isEmpty();
        }

        List<Item> unsupported() {
            List<Item> result = new ArrayList<>();
            for (Item item : items) {
                if (item.support == Support.UNSUPPORTED) result.add(item);
            }
            return result;
        }

        /** Appends the visible low-confidence label after each number only low-confidence evidence supports. */
        String label(String speech) {
            StringBuilder labelled = new StringBuilder(speech);
            for (int i = items.size() - 1; i >= 0; i--) {
                Item item = items.get(i);
                if (item.support == Support.LOW_CONFIDENCE) {
                    labelled.insert(item.token.end, LOW_CONFIDENCE_LABEL);
                }
            }
            return labelled.toString();
        }

        JSONObject toJson(int rewriteCount) {
            JSONObject json = new JSONObject(true);
            JSONArray list = new JSONArray();
            int verified = 0;
            int lowConfidence = 0;
            int unsupported = 0;
            for (Item item : items) {
                if (item.support == Support.VERIFIED) verified++;
                else if (item.support == Support.LOW_CONFIDENCE) lowConfidence++;
                else unsupported++;
                JSONObject entry = new JSONObject(true);
                entry.put("text", item.token.text);
                entry.put("support", item.support.name());
                list.add(entry);
            }
            json.put("checkedCount", items.size());
            json.put("verifiedCount", verified);
            json.put("lowConfidenceCount", lowConfidence);
            json.put("unsupportedCount", unsupported);
            json.put("rewriteCount", rewriteCount);
            json.put("items", list);
            return json;
        }
    }

    static final class Item {
        private final Token token;
        private final Support support;

        private Item(Token token, Support support) {
            this.token = token;
            this.support = support;
        }
    }

    private static final class Token {
        private final String text;
        private final int start;
        private final int end;
        private final BigDecimal displayed;
        private final BigDecimal multiplier;
        private final BigDecimal value;
        private final String unit;

        private Token(String text, int start, int end, BigDecimal displayed, BigDecimal multiplier,
                      BigDecimal value, String unit) {
            this.text = text;
            this.start = start;
            this.end = end;
            this.displayed = displayed;
            this.multiplier = multiplier;
            this.value = value;
            this.unit = unit;
        }
    }
}
