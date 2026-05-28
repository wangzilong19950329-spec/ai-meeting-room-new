package com.aimeeting.room.service;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 会议结论通知服务（Server酱微信推送）
 * 在 application.yml 中配置 notification.serverchan-key 后生效
 */
@Slf4j
@Service
public class NotificationService {

    @Value("${notification.serverchan-key:}")
    private String serverChanKey;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    /**
     * 发送会议结论通知（微信推送）
     *
     * @param meetingTitle 会议标题
     * @param conclusion   最终结论
     */
    public void sendMeetingConclusion(String meetingTitle, String conclusion) {
        if (serverChanKey == null || serverChanKey.trim().isEmpty()) {
            log.debug("Server酱 key 未配置，跳过通知");
            return;
        }
        try {
            String url = "https://sctapi.ftqq.com/" + serverChanKey.trim() + ".send";
            String title = "AI 会议室 · 结论已生成";
            String desp = "**议题：** " + meetingTitle + "\n\n**最终结论：**\n" + conclusion;

            RequestBody body = new FormBody.Builder()
                    .add("title", title)
                    .add("desp", desp)
                    .build();
            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    log.info("微信通知发送成功: meeting={}", meetingTitle);
                } else {
                    log.warn("微信通知失败: code={}", response.code());
                }
            }
        } catch (Exception e) {
            log.warn("微信通知异常: {}", e.getMessage());
        }
    }
}
