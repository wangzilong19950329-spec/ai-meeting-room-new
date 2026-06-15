package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/stock-watch/signals")
@RequiredArgsConstructor
public class StockWatchSignalController {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private final NotificationService notificationService;

    /**
     * Simulates the real stock-watch path:
     * market/rule snapshot -> formatted notification channel.
     */
    @PostMapping("/simulate")
    public ApiResponse<Map<String, Object>> simulateSignal(@RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> request = body == null ? new LinkedHashMap<>() : body;
        String symbol = text(request, "symbol", "688017");
        String name = text(request, "name", "绿的谐波");
        String triggerType = text(request, "triggerType", "BREAK_SUPPORT");
        String severity = text(request, "severity", "risk");
        double price = number(request.get("price"), 258.0);
        double support = number(request.get("support"), 287.0);
        int breakDays = integer(request.get("breakDays"), 5);
        int requiredBreakDays = integer(request.get("requiredBreakDays"), 5);
        boolean notify = bool(request.get("notify"), true);
        boolean forceTrigger = bool(request.get("forceTrigger"), false);

        boolean supportBroken = price < support;
        boolean breakDaysMet = breakDays >= requiredBreakDays;
        boolean triggered = forceTrigger || (supportBroken && breakDaysMet);
        String simulatedAt = ZonedDateTime.now(CHINA_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String eventId = "sim_" + symbol + "_" + triggerType + "_" + System.currentTimeMillis();
        String reason = buildReason(triggered, price, support, breakDays, requiredBreakDays, forceTrigger);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", eventId);
        event.put("timestamp", simulatedAt);
        event.put("symbol", symbol);
        event.put("name", name);
        event.put("trigger_type", triggerType);
        event.put("severity", severity);
        event.put("price", price);
        event.put("support", support);
        event.put("break_days", breakDays);
        event.put("required_break_days", requiredBreakDays);
        event.put("triggered", triggered);
        event.put("reason", reason);

        boolean emailSent = false;
        boolean notificationAttempted = triggered && notify;
        if (notificationAttempted) {
            String subject = "Stock Watch 预警 · " + name + " " + triggerType;
            StringBuilder content = new StringBuilder();
            content.append("Stock Watch 预警模拟\n")
                    .append("====================\n\n")
                    .append("一、触发摘要\n")
                    .append("  标的：").append(name).append("（").append(symbol).append("）\n")
                    .append("  信号：").append(triggerType).append("\n")
                    .append("  级别：").append(severity).append("\n")
                    .append("  时间：").append(simulatedAt).append("\n\n")
                    .append("二、关键行情\n")
                    .append("  最新价：").append(format(price)).append("\n")
                    .append("  支撑位：").append(format(support)).append("\n")
                    .append("  跌破天数：").append(breakDays).append("/").append(requiredBreakDays).append("\n\n")
                    .append("三、触发原因\n")
                    .append("  - ").append(reason).append("\n");
            emailSent = notificationService.sendStockWatchAlert(subject, content.toString());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("triggered", triggered);
        result.put("notificationAttempted", notificationAttempted);
        result.put("emailSent", emailSent);
        result.put("event", event);
        result.put("message", triggered
                ? (emailSent ? "信号已触发，邮件已发送" : "信号已触发，但邮件未发送")
                : "信号未触发，未发送邮件");
        log.info("stock-watch signal simulation: symbol={}, triggerType={}, triggered={}, emailSent={}",
                symbol, triggerType, triggered, emailSent);
        return ApiResponse.ok(result);
    }

    private String buildReason(boolean triggered, double price, double support, int breakDays, int requiredBreakDays, boolean forceTrigger) {
        if (forceTrigger) {
            return "forceTrigger=true，强制模拟信号触发。";
        }
        if (triggered) {
            return "price=" + format(price) + " 低于 support=" + format(support)
                    + "，且 break_days=" + breakDays + "/" + requiredBreakDays + " 达到触发条件。";
        }
        return "未同时满足 price < support 与 break_days >= required_break_days：price=" + format(price)
                + "，support=" + format(support) + "，break_days=" + breakDays + "/" + requiredBreakDays + "。";
    }

    private String text(Map<String, Object> request, String key, String fallback) {
        Object value = request.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private double number(Object raw, double fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number) return ((Number) raw).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int integer(Object raw, int fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Number) return ((Number) raw).intValue();
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean bool(Object raw, boolean fallback) {
        if (raw == null) return fallback;
        if (raw instanceof Boolean) return (Boolean) raw;
        String value = String.valueOf(raw).trim().toLowerCase();
        if ("true".equals(value) || "1".equals(value) || "yes".equals(value)) return true;
        if ("false".equals(value) || "0".equals(value) || "no".equals(value)) return false;
        return fallback;
    }

    private String format(double value) {
        return String.format("%.2f", value);
    }
}
