package com.aimeeting.room.service;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.config.ModelEntry;
import com.aimeeting.room.config.ModelRegistryStore;
import com.aimeeting.room.controller.MeetingController;
import com.aimeeting.room.dto.ChatResult;
import com.aimeeting.room.dto.MeetingUsageVO;
import com.aimeeting.room.service.ai.GenericAiClient;
import com.aimeeting.room.service.ai.WebSearchService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Every meeting-attributed model or GEN-10 call leaves one usage row; other calls leave none. */
@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
class MeetingCallLogIntegrationTest {
    private final long meetingId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    private HttpServer server;

    @Autowired private MeetingCallLogService callLog;
    @Autowired private MeetingController controller;

    @AfterEach
    void cleanup() {
        if (server != null) server.stop(0);
        callLog.deleteByMeetingId(meetingId);
    }

    @Test
    void openAiUsageIsRecordedWithMeetingRoundMemberAndStage() throws Exception {
        String url = serve("{\"choices\":[{\"message\":{\"content\":\"好的\"}}],"
                + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":30,\"prompt_cache_hit_tokens\":100}}");
        GenericAiClient client = client(url, "openai");

        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, 2, 3L, MeetingCallContext.SPEECH)) {
            assertEquals("好的", client.chatWithEvidenceStrict("test-model", "系统", "用户", null).content);
        }

        MeetingUsageVO.UsageLine total = callLog.usage(meetingId).getTotal();
        assertEquals(1, total.getCalls());
        assertEquals(1, total.getMeteredCalls());
        assertEquals(120, total.getPromptTokens());
        assertEquals(30, total.getCompletionTokens());
        assertEquals(100, total.getCacheHitTokens());
        MeetingUsageVO.UsageLine speech = callLog.usage(meetingId).getStages().get(0);
        assertEquals(MeetingCallContext.SPEECH, speech.getStage());
    }

    @Test
    void claudeUsageIsRecorded() throws Exception {
        String url = serve("{\"content\":[{\"type\":\"text\",\"text\":\"好\"}],"
                + "\"usage\":{\"input_tokens\":50,\"output_tokens\":7,\"cache_read_input_tokens\":20}}");
        GenericAiClient client = client(url, "claude");

        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, 1, null, MeetingCallContext.SUMMARY)) {
            assertEquals("好", client.chatWithEvidenceStrict("test-model", "系统", "用户", null).content);
        }

        MeetingUsageVO.UsageLine total = callLog.usage(meetingId).getTotal();
        assertEquals(1, total.getCalls());
        assertEquals(50, total.getPromptTokens());
        assertEquals(7, total.getCompletionTokens());
        assertEquals(20, total.getCacheHitTokens());
    }

    @Test
    void callsOutsideAMeetingAreNotRecorded() throws Exception {
        String url = serve("{\"choices\":[{\"message\":{\"content\":\"好的\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
        GenericAiClient client = client(url, "openai");

        assertEquals("好的", client.chatWithEvidenceStrict("test-model", "系统", "用户", null).content);

        assertEquals(0, callLog.usage(meetingId).getTotal().getCalls());
    }

    @Test
    void recorderFailureDoesNotBreakTheModelCall() throws Exception {
        String url = serve("{\"choices\":[{\"message\":{\"content\":\"好的\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
        GenericAiClient client = client(url, "openai");
        MeetingCallLogService broken = mock(MeetingCallLogService.class, invocation -> {
            throw new IllegalStateException("database down");
        });
        ReflectionTestUtils.setField(client, "callLog", broken);

        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, 1, null, MeetingCallContext.VOTE)) {
            assertEquals("好的", client.chatWithEvidenceStrict("test-model", "系统", "用户", null).content);
        }
    }

    @Test
    void webBridgeCallsRecordCharactersButNoTokens() {
        MeetingOrchestratorService orchestrator = new MeetingOrchestratorService();
        MeetingDeepSeekWebBridgeService bridge = mock(MeetingDeepSeekWebBridgeService.class);
        when(bridge.routeDecision("test-model")).thenReturn(MeetingDeepSeekWebBridgeService.RouteDecision.BRIDGE);
        when(bridge.chatWithoutSearch(anyString(), anyString(), anyString(), nullable(Integer.class)))
                .thenReturn(new ChatResult("三个字"));
        ReflectionTestUtils.setField(orchestrator, "meetingDeepSeekWebBridgeService", bridge);
        ReflectionTestUtils.setField(orchestrator, "modelRegistryStore", registry("http://unused", "openai"));
        ReflectionTestUtils.setField(orchestrator, "callLog", callLog);

        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, 1, 4L, MeetingCallContext.SPEECH)) {
            assertEquals("三个字", orchestrator.callModelExStrict("test-model", "系统", "用户").content);
        }

        MeetingUsageVO.UsageLine total = callLog.usage(meetingId).getTotal();
        assertEquals(1, total.getCalls());
        assertEquals(1, total.getUnmeteredCalls());
        assertEquals(0, total.getPromptTokens());
        assertEquals(4, total.getPromptChars());
        assertEquals(3, total.getCompletionChars());
    }

    @Test
    void gen10SearchIsRecordedWithItsStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            JSONObject request = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = crossCheckedPacket(request.getString("requestId"), request.getString("question"),
                    request.getString("asOf")).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        MeetingReviewedEvidenceService evidence = new MeetingReviewedEvidenceService(true,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook", "local-test-token");
        ReflectionTestUtils.setField(evidence, "callLog", callLog);

        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, 1, 5L,
                MeetingCallContext.EVIDENCE_SEARCH)) {
            evidence.search("meeting-call-log-test", "公开事实是什么？", "2026-10-17", 30_000L);
        }

        MeetingUsageVO.UsageLine line = callLog.usage(meetingId).getStages().get(0);
        assertEquals(MeetingCallContext.EVIDENCE_SEARCH, line.getStage());
        assertEquals(1, line.getCalls());
        assertEquals(1, line.getUnmeteredCalls());
    }

    @Test
    void usageEndpointReturnsTotalAndPerStageLines() {
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, 1, 3L, MeetingCallContext.SPEECH)) {
            callLog.record(MeetingCallLogService.CHANNEL_API, "m", 100L, "success", 100, 20, 80, 400, 40);
        }
        try (MeetingCallContext.Scope ignored = MeetingCallContext.enter(meetingId, 1, 3L, MeetingCallContext.VOTE)) {
            callLog.record(MeetingCallLogService.CHANNEL_WEB_BRIDGE, "m", 200L, "success", null, null, null, 50, 10);
        }

        ApiResponse<MeetingUsageVO> response = controller.getMeetingUsage(meetingId);

        MeetingUsageVO.UsageLine total = response.getData().getTotal();
        assertNull(total.getStage());
        assertEquals(2, total.getCalls());
        assertEquals(1, total.getMeteredCalls());
        assertEquals(1, total.getUnmeteredCalls());
        assertEquals(100, total.getPromptTokens());
        assertEquals(20, total.getCompletionTokens());
        assertEquals(80, total.getCacheHitTokens());
        assertEquals(450, total.getPromptChars());
        assertEquals(50, total.getCompletionChars());
        assertEquals(300, total.getDurationMs());
        assertEquals(2, response.getData().getStages().size());
    }

    private String serve(String body) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private GenericAiClient client(String url, String format) {
        GenericAiClient client = new GenericAiClient();
        ReflectionTestUtils.setField(client, "registry", registry(url, format));
        ReflectionTestUtils.setField(client, "webSearch", mock(WebSearchService.class));
        ReflectionTestUtils.setField(client, "callLog", callLog);
        return client;
    }

    private static ModelRegistryStore registry(String url, String format) {
        ModelEntry entry = new ModelEntry();
        entry.setId("test");
        entry.setModelId("test-model");
        entry.setApiUrl(url);
        entry.setApiKey("k");
        entry.setApiFormat(format);
        entry.setEnabled(true);
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        when(registry.getByModelId(any())).thenReturn(entry);
        return registry;
    }

    private static String crossCheckedPacket(String requestId, String question, String asOf) {
        JSONObject packet = JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                + "\"status\":\"CROSS_CHECKED\",\"audit\":{\"reviewCalls\":1,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"FOUND\",\"searchCount\":1,"
                + "\"ids\":[\"fact\"],\"links\":[\"https://example.org/source\"]},"
                + "{\"phase\":\"review\",\"id\":\"fact\",\"verdict\":\"PASS\","
                + "\"reviewerVerdict\":\"PASS\",\"searchCount\":1,"
                + "\"links\":[\"https://example.org/review\"]}]},"
                + "\"claims\":[{\"id\":\"fact\",\"statement\":\"经交叉搜索核验的公开事实\","
                + "\"sourceUrls\":[\"https://example.org/source\"],"
                + "\"reviewLinks\":[\"https://example.org/review\"],"
                + "\"reviewMethod\":\"TWO_ISOLATED_DEEPSEEK_SEARCHES\"}]}");
        packet.put("requestId", requestId);
        packet.put("question", question);
        packet.put("asOf", asOf);
        return packet.toJSONString();
    }
}
