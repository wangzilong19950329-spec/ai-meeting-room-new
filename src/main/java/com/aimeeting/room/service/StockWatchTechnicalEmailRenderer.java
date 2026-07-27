package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.text.SimpleDateFormat;
import java.util.Locale;

@Component
public class StockWatchTechnicalEmailRenderer {

    private static final long EPOCH_MILLIS_THRESHOLD = 100_000_000_000L;
    private final StockWatchRiskPatternSummaryRenderer riskPatternSummaryRenderer = new StockWatchRiskPatternSummaryRenderer();

    public String render(StockWatchTechnicalAnalysis source, String contextLabel) {
        if (source == null || !TrustedTechnicalRendererV1.isTrustedV6(source)) return "";
        TrustedTechnicalRendererV1.applyTrustedView(source);
        StringBuilder body = new StringBuilder();
        body.append("\n").append(contextLabel).append("\n");
        body.append("  分析时间：").append(formatDate(source)).append("\n");
        body.append("  标题：").append(text(source.getTitle(), "技术面分析结论")).append("\n");
        body.append("  立场：").append(text(source.getStance(), "--")).append("\n");
        body.append("  情绪：").append(text(source.getTone(), "--")).append("\n");
        body.append("  置信度：").append(source.getConfidence() == null
                ? "--" : String.format(Locale.ROOT, "%.2f", source.getConfidence())).append("\n");
        body.append("  服务器可信技术结论：\n    ")
                .append(text(source.getConclusion(), "冻结技术证据不足，无法形成可信方向结论。"))
                .append("\n");
        JSONObject intent = validIntent(source);
        if (intent != null) {
            body.append("  主力意图：").append(text(intent.getString("intent"), "UNKNOWN")).append("\n");
            body.append("  意图窗口：").append(text(intent.getString("timeWindow"), "--")).append("\n");
            if (StringUtils.hasText(intent.getString("risk"))) {
                body.append("  风险：").append(intent.getString("risk").trim()).append("\n");
            }
        }
        String riskPatternBlock = riskPatternSummaryRenderer.renderMailBlock(
                riskPatternSummaryRenderer.summarizeFromSnapshotJson(source.getDataSnapshotJson()), "    ");
        if (StringUtils.hasText(riskPatternBlock)) {
            body.append("  价量风险结构识别（套路识别）：\n").append(riskPatternBlock);
        }
        JSONArray evidence = parseArray(source.getEvidenceJson());
        if (evidence != null && !evidence.isEmpty()) {
            body.append("  关键依据：\n");
            for (int i = 0; i < evidence.size(); i++) {
                String item = evidence.getString(i);
                if (StringUtils.hasText(item)) body.append("    - ").append(item.trim()).append("\n");
            }
        }
        if (StringUtils.hasText(source.getConversationUrl())) {
            body.append("  DeepSeek 原会话：").append(source.getConversationUrl().trim()).append("\n");
        }
        return body.toString();
    }

    public Long sourceTimestampEpochSeconds(StockWatchTechnicalAnalysis analysis) {
        if (analysis == null || !StringUtils.hasText(analysis.getEvidenceSignalJson())) return null;
        try {
            JSONObject signals = JSON.parseObject(analysis.getEvidenceSignalJson());
            return signals == null ? null : normalizeEpochSeconds(signals.get("sourceTimestamp"));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Long normalizeEpochSeconds(Object raw) {
        if (raw == null) return null;
        try {
            long value = raw instanceof Number ? ((Number) raw).longValue()
                    : Long.parseLong(String.valueOf(raw).trim());
            return value > EPOCH_MILLIS_THRESHOLD ? value / 1000L : value;
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONObject validIntent(StockWatchTechnicalAnalysis analysis) {
        String status = analysis.getMainForceIntentStatus();
        if (!"VALID".equalsIgnoreCase(status) && !"VALID_WITH_WARNINGS".equalsIgnoreCase(status)) return null;
        try {
            return JSON.parseObject(analysis.getMainForceIntentJson());
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONArray parseArray(String value) {
        try {
            return StringUtils.hasText(value) ? JSON.parseArray(value) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String formatDate(StockWatchTechnicalAnalysis analysis) {
        if (analysis.getGmtCreate() != null) {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm").format(analysis.getGmtCreate());
        }
        return analysis.getAnalysisDate() == null
                ? "--" : new SimpleDateFormat("yyyy-MM-dd").format(analysis.getAnalysisDate());
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
