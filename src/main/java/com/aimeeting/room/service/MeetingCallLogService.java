package com.aimeeting.room.service;

import com.aimeeting.room.dao.AiMeetingCallLogMapper;
import com.aimeeting.room.dto.MeetingUsageVO;
import com.aimeeting.room.entity.AiMeetingCallLog;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Records meeting-attributed model and GEN-10 calls and summarises their usage. Recording never
 * fails the caller: a call made outside a {@link MeetingCallContext} is ignored, and storage
 * errors are logged and swallowed.
 */
@Slf4j
@Service
public class MeetingCallLogService {

    public static final String CHANNEL_API = "api";
    public static final String CHANNEL_WEB_BRIDGE = "web_bridge";
    public static final String CHANNEL_GEN10 = "gen10";

    @Autowired private AiMeetingCallLogMapper mapper;

    public void record(String channel, String model, long durationMs, String status,
                       Integer promptTokens, Integer completionTokens, Integer cacheHitTokens,
                       Integer promptChars, Integer completionChars) {
        MeetingCallContext.Frame frame = MeetingCallContext.current();
        if (frame == null || frame.meetingId == null) return;
        try {
            AiMeetingCallLog row = new AiMeetingCallLog();
            row.setMeetingId(frame.meetingId);
            row.setRoundNumber(frame.round);
            row.setMemberId(frame.memberId);
            row.setStage(frame.stage);
            row.setChannel(channel);
            row.setModel(truncate(model, 128));
            row.setStatus(truncate(status, 64));
            row.setDurationMs(Math.max(0L, durationMs));
            row.setPromptTokens(promptTokens);
            row.setCompletionTokens(completionTokens);
            row.setCacheHitTokens(cacheHitTokens);
            row.setPromptChars(promptChars);
            row.setCompletionChars(completionChars);
            mapper.insert(row);
        } catch (RuntimeException storageFailure) {
            log.warn("Meeting call usage was not recorded for meeting {}: {}",
                    frame.meetingId, storageFailure.getMessage());
        }
    }

    /**
     * Records a model-API call, reading provider-reported usage from either the OpenAI-compatible
     * shape (prompt_tokens, completion_tokens, prompt_cache_hit_tokens or cached_tokens) or the
     * Claude shape (input_tokens, output_tokens, cache_read_input_tokens).
     */
    public void recordApiCall(String model, long durationMs, String status,
                              JSONObject requestBody, String responseBody) {
        if (MeetingCallContext.current() == null) return;
        Integer prompt = null;
        Integer completion = null;
        Integer cacheHit = null;
        Integer completionChars = null;
        try {
            JSONObject response = responseBody == null || responseBody.isBlank() ? null : JSON.parseObject(responseBody);
            JSONObject usage = response == null ? null : response.getJSONObject("usage");
            if (usage != null) {
                if (usage.containsKey("input_tokens")) {
                    prompt = usage.getInteger("input_tokens");
                    completion = usage.getInteger("output_tokens");
                    cacheHit = usage.getInteger("cache_read_input_tokens");
                } else {
                    prompt = usage.getInteger("prompt_tokens");
                    completion = usage.getInteger("completion_tokens");
                    cacheHit = usage.getInteger("prompt_cache_hit_tokens");
                    JSONObject details = usage.getJSONObject("prompt_tokens_details");
                    if (cacheHit == null && details != null) cacheHit = details.getInteger("cached_tokens");
                }
            }
            completionChars = response == null ? null : completionChars(response);
        } catch (RuntimeException unparsable) {
            // Keep the timing even when the body is not JSON.
        }
        record(CHANNEL_API, model, durationMs, status, prompt, completion, cacheHit,
                promptChars(requestBody), completionChars);
    }

    public MeetingUsageVO usage(Long meetingId) {
        MeetingUsageVO usage = new MeetingUsageVO();
        Map<String, MeetingUsageVO.UsageLine> byStage = new LinkedHashMap<>();
        List<AiMeetingCallLog> rows = mapper.selectByMeetingId(meetingId);
        if (rows == null) return usage;
        for (AiMeetingCallLog row : rows) {
            add(usage.getTotal(), row);
            MeetingUsageVO.UsageLine line = byStage.computeIfAbsent(row.getStage(), stage -> {
                MeetingUsageVO.UsageLine created = new MeetingUsageVO.UsageLine();
                created.setStage(stage);
                return created;
            });
            add(line, row);
        }
        usage.getStages().addAll(byStage.values());
        return usage;
    }

    public void deleteByMeetingId(Long meetingId) {
        mapper.deleteByMeetingId(meetingId);
    }

    private static void add(MeetingUsageVO.UsageLine line, AiMeetingCallLog row) {
        line.setCalls(line.getCalls() + 1);
        boolean metered = row.getPromptTokens() != null || row.getCompletionTokens() != null;
        if (metered) line.setMeteredCalls(line.getMeteredCalls() + 1);
        else line.setUnmeteredCalls(line.getUnmeteredCalls() + 1);
        line.setPromptTokens(line.getPromptTokens() + value(row.getPromptTokens()));
        line.setCompletionTokens(line.getCompletionTokens() + value(row.getCompletionTokens()));
        line.setCacheHitTokens(line.getCacheHitTokens() + value(row.getCacheHitTokens()));
        line.setPromptChars(line.getPromptChars() + value(row.getPromptChars()));
        line.setCompletionChars(line.getCompletionChars() + value(row.getCompletionChars()));
        line.setDurationMs(line.getDurationMs() + (row.getDurationMs() == null ? 0L : row.getDurationMs()));
    }

    private static long value(Integer number) {
        return number == null ? 0L : number;
    }

    private static Integer promptChars(JSONObject requestBody) {
        if (requestBody == null) return null;
        int chars = length(requestBody.getString("system"));
        JSONArray messages = requestBody.getJSONArray("messages");
        if (messages != null) {
            for (int i = 0; i < messages.size(); i++) {
                JSONObject message = messages.getJSONObject(i);
                if (message != null) chars += length(message.getString("content"));
            }
        }
        return chars;
    }

    private static Integer completionChars(JSONObject response) {
        JSONArray choices = response.getJSONArray("choices");
        if (choices != null && !choices.isEmpty()) {
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            return message == null ? null : length(message.getString("content"));
        }
        JSONArray content = response.getJSONArray("content");
        if (content == null) return null;
        int chars = 0;
        for (int i = 0; i < content.size(); i++) {
            JSONObject block = content.getJSONObject(i);
            if (block != null && "text".equals(block.getString("type"))) chars += length(block.getString("text"));
        }
        return chars;
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }

    private static String truncate(String text, int limit) {
        return text == null || text.length() <= limit ? text : text.substring(0, limit);
    }
}
