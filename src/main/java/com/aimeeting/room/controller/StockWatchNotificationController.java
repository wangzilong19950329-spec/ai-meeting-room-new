package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/stock-watch/notify")
@RequiredArgsConstructor
public class StockWatchNotificationController {

    private final NotificationService notificationService;

    @PostMapping("/email")
    public ApiResponse<Map<String, Object>> sendEmail(@RequestBody(required = false) Map<String, String> body) {
        String subject = body != null ? body.get("subject") : null;
        String content = body != null ? body.get("content") : null;
        boolean sent = notificationService.sendStockWatchAlert(
                firstNonBlank(subject, "Stock Watch 预警"),
                firstNonBlank(content, "Stock Watch 触发了一条新的预警事件。")
        );
        if (!sent) {
            return ApiResponse.fail("邮件未发送：请确认 NOTIFICATION_EMAIL_ENABLED=true、SMTP_HOST/SMTP_USERNAME/SMTP_PASSWORD、NOTIFICATION_EMAIL_TO 已配置，并查看后端日志");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sent", true);
        result.put("channel", "email");
        return ApiResponse.ok(result);
    }

    @PostMapping("/test-email")
    public ApiResponse<Map<String, Object>> sendTestEmail() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("subject", "Stock Watch · 邮件通知测试");
        body.put("content", "如果你收到这封邮件，说明 Stock Watch 邮件通知链路已经配置成功。");
        return sendEmail(body);
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
