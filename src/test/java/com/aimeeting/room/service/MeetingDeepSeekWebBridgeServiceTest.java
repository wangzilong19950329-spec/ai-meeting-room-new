package com.aimeeting.room.service;

import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.config.ModelEntry;
import com.aimeeting.room.config.ModelRegistryStore;
import com.aimeeting.room.dto.ChatResult;
import com.aimeeting.room.service.ai.AiModelRouter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MeetingDeepSeekWebBridgeServiceTest {

    @Test
    void routeDecisionCoversDeepSeekEntriesAndProtectsLegacySelectors() {
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        AiModelProperties props = props(true, "deepseek-chat");
        MeetingDeepSeekWebBridgeService service = service(registry, props);

        when(registry.getByModelId("deepseek-chat"))
                .thenReturn(entry("ff155db1", "deepseek-chat", "https://api.deepseek.com/v1/chat/completions", "openai", true));
        when(registry.getByModelId("deepseek-reasoner"))
                .thenReturn(entry("d87c9a6d", "deepseek-reasoner", "https://api.deepseek.com/v1/chat/completions", "openai", true));
        when(registry.getByModelId("b6465a2d"))
                .thenReturn(entry("b6465a2d", "deepseek-v4-pro", "https://api.deepseek.com/v1/chat/completions", "openai", true));
        when(registry.getByModelId("9fb32460"))
                .thenReturn(entry("9fb32460", "deepseek-v4-pro", "https://api.deepseek.com/anthropic/v1/messages", "claude", true));

        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.BRIDGE, service.routeDecision("deepseek-chat"));
        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.BRIDGE, service.routeDecision("deepseek-reasoner"));
        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.BRIDGE, service.routeDecision("b6465a2d"));
        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.BRIDGE, service.routeDecision("9fb32460"));
        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.BRIDGE, service.routeDecision(null));

        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.PASS_THROUGH, service.routeDecision("gpt-4o-mini"));
        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.PASS_THROUGH, service.routeDecision("claude-3-opus"));
        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.PASS_THROUGH, service.routeDecision("ernie-bot-4"));
        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.BRIDGE, service.routeDecision("deepseek-v4-pro"));
        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.FAIL_CLOSED, service.routeDecision("custom-unknown-model"));
    }

    @Test
    void routeDecisionFailClosesDisabledNonLegacyEntriesWhenDefaultIsDeepSeek() {
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        AiModelProperties props = props(true, "deepseek-chat");
        MeetingDeepSeekWebBridgeService service = service(registry, props);

        when(registry.getByModelId("deepseek-chat"))
                .thenReturn(entry("ff155db1", "deepseek-chat", "https://api.deepseek.com/v1/chat/completions", "openai", true));
        when(registry.getByModelId("disabled-deepseek"))
                .thenReturn(entry("disabled-deepseek", "deepseek-chat", "https://api.deepseek.com/v1/chat/completions", "openai", false));
        when(registry.getByModelId("custom-disabled"))
                .thenReturn(entry("custom-disabled", "custom-model", "https://example.com/v1/chat/completions", "openai", false));
        when(registry.getByModelId("gpt-disabled"))
                .thenReturn(entry("gpt-disabled", "gpt-disabled", "https://example.com/v1/chat/completions", "openai", false));

        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.FAIL_CLOSED, service.routeDecision("disabled-deepseek"));
        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.FAIL_CLOSED, service.routeDecision("custom-disabled"));
        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.PASS_THROUGH, service.routeDecision("gpt-disabled"));
    }

    @Test
    void unknownSelectorPassesThroughWhenDefaultIsNotDeepSeek() {
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        AiModelProperties props = props(true, "gpt-4o");
        MeetingDeepSeekWebBridgeService service = service(registry, props);
        when(registry.getByModelId("gpt-4o"))
                .thenReturn(entry("gpt-4o", "gpt-4o", "https://api.openai.com/v1/chat/completions", "openai", true));

        assertEquals(MeetingDeepSeekWebBridgeService.RouteDecision.PASS_THROUGH, service.routeDecision("custom-unknown-model"));
    }

    @Test
    void bridgeCallSetsExpectedJsonAndUsesExpertModeForNormalGeneration() throws Exception {
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        AiModelProperties props = props(true, "deepseek-chat");
        when(registry.getByModelId("b6465a2d"))
                .thenReturn(entry("b6465a2d", "deepseek-v4-pro", "https://api.deepseek.com/v1/chat/completions", "openai", true));
        MeetingDeepSeekWebBridgeService service = service(registry, props);

        List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = bridgeServer(requestBodies,
                "{\"ok\":true,\"result\":{\"answerText\":\"{\\\"ok\\\":true}\",\"contentStatus\":\"success\"}}",
                0,
                null,
                null);
        try {
            pointBridge(service, props, server);

            ChatResult result = service.chat("b6465a2d", "严格输出 JSON，不要输出 JSON 以外的文字。", "给出 {\"ok\":true}", 300);

            assertEquals("{\"ok\":true}", result.content);
            assertTrue(requestBodies.get(0).contains("\"expectedJson\":true"));
            assertTrue(requestBodies.get(0).contains("\"mode\":\"expert\""));
            assertFalse(requestBodies.get(0).contains("isolatedSession"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bridgeCallDoesNotExpectJsonJustBecausePromptContainsStructuredEvidence() throws Exception {
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        AiModelProperties props = props(true, "deepseek-chat");
        when(registry.getByModelId("b6465a2d"))
                .thenReturn(entry("b6465a2d", "deepseek-v4-pro", "https://api.deepseek.com/v1/chat/completions", "openai", true));
        MeetingDeepSeekWebBridgeService service = service(registry, props);

        List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = bridgeServer(requestBodies,
                "{\"ok\":true,\"result\":{\"answerText\":\"这是自然语言会议发言。\",\"contentStatus\":\"success\"}}",
                0,
                null,
                null);
        try {
            pointBridge(service, props, server);

            ChatResult result = service.chat(
                    "b6465a2d",
                    "你是圆桌会议成员，请输出自然语言分析。",
                    "本轮证据：{\"quote\":{\"price\":24.8},\"facts\":[\"MA20\"]}。请基于证据给出观点。",
                    800
            );

            assertEquals("这是自然语言会议发言。", result.content);
            assertTrue(requestBodies.get(0).contains("\"expectedJson\":false"));
            assertTrue(requestBodies.get(0).contains("\"mode\":\"expert\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void searchExecutorUsesFastModeAndMapsLinksToEvidence() throws Exception {
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        AiModelProperties props = props(true, "deepseek-chat");
        when(registry.getByModelId("9fb32460"))
                .thenReturn(entry("9fb32460", "deepseek-v4-pro", "https://api.deepseek.com/anthropic/v1/messages", "claude", true));
        MeetingDeepSeekWebBridgeService service = service(registry, props);

        List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());
        String response = "{\"ok\":true,\"result\":{\"answerText\":\"已检索到来源。\","
                + "\"contentStatus\":\"success\",\"links\":[{\"href\":\"https://example.com/a\",\"text\":\"来源A\"}]}}";
        HttpServer server = bridgeServer(requestBodies, response, 0, null, null);
        try {
            pointBridge(service, props, server);

            ChatResult result = service.chat(
                    "9fb32460",
                    "你是会议流程里的联网搜索执行器。你只负责执行网页搜索并返回检索证据。",
                    "你现在只执行联网搜索，不做分析。",
                    null
            );

            assertEquals("已检索到来源。", result.content);
            assertTrue(requestBodies.get(0).contains("\"mode\":\"fast\""));
            assertTrue(result.hasSearchEvidence());
            assertEquals(2, result.searchResults.size());
            assertEquals("DeepSeek 网页智能搜索摘要", result.searchResults.get(0).source);
            assertEquals("https://example.com/a", result.searchResults.get(1).url);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void bridgeFailuresFailClosedWithoutRouterFallback() throws Exception {
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        AiModelProperties props = props(true, "deepseek-chat");
        when(registry.getByModelId("b6465a2d"))
                .thenReturn(entry("b6465a2d", "deepseek-v4-pro", "https://api.deepseek.com/v1/chat/completions", "openai", true));
        MeetingDeepSeekWebBridgeService bridgeService = service(registry, props);
        AiModelRouter router = mock(AiModelRouter.class);
        MeetingOrchestratorService orchestrator = orchestrator(router, bridgeService);

        HttpServer server = bridgeServer(new ArrayList<>(), "{\"ok\":false}", 500, null, null);
        try {
            pointBridge(bridgeService, props, server);

            String content = orchestrator.callModel("b6465a2d", "system", "user", 200);

            assertNull(content);
            verifyNoInteractions(router);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void orchestratorPassesThroughWhenSwitchIsOffOrLegacySelectorMatches() {
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        AiModelProperties props = props(false, "deepseek-chat");
        MeetingDeepSeekWebBridgeService bridgeService = service(registry, props);
        AiModelRouter router = mock(AiModelRouter.class);
        MeetingOrchestratorService orchestrator = orchestrator(router, bridgeService);
        when(router.chat(eq("b6465a2d"), anyString(), anyString(), any())).thenReturn("router-off");

        assertEquals("router-off", orchestrator.callModel("b6465a2d", "system", "user", 200));
        verify(router).chat(eq("b6465a2d"), anyString(), anyString(), eq(200));

        props.getMeeting().getDeepseekWebBridge().setEnabled(true);
        when(router.chat(eq("gpt-4o-mini"), anyString(), anyString(), any())).thenReturn("router-legacy");

        assertEquals("router-legacy", orchestrator.callModel("gpt-4o-mini", "system", "user", 200));
        verify(router).chat(eq("gpt-4o-mini"), anyString(), anyString(), eq(200));
    }

    @Test
    void orchestratorFailClosesUnknownSelectorWhenDefaultIsDeepSeek() {
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        AiModelProperties props = props(true, "deepseek-chat");
        when(registry.getByModelId("deepseek-chat"))
                .thenReturn(entry("ff155db1", "deepseek-chat", "https://api.deepseek.com/v1/chat/completions", "openai", true));
        MeetingDeepSeekWebBridgeService bridgeService = service(registry, props);
        AiModelRouter router = mock(AiModelRouter.class);
        MeetingOrchestratorService orchestrator = orchestrator(router, bridgeService);

        assertNull(orchestrator.callModel("custom-unknown-model", "system", "user"));
        verifyNoInteractions(router);
    }

    @Test
    void serializedBridgeCallsDoNotEnterServerConcurrently() throws Exception {
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        AiModelProperties props = props(true, "deepseek-chat");
        when(registry.getByModelId("b6465a2d"))
                .thenReturn(entry("b6465a2d", "deepseek-v4-pro", "https://api.deepseek.com/v1/chat/completions", "openai", true));
        MeetingDeepSeekWebBridgeService service = service(registry, props);

        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        HttpServer server = bridgeServer(
                Collections.synchronizedList(new ArrayList<>()),
                "{\"ok\":true,\"result\":{\"answerText\":\"ok\",\"contentStatus\":\"success\"}}",
                0,
                active,
                maxActive
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            pointBridge(service, props, server);
            CountDownLatch start = new CountDownLatch(1);
            Future<ChatResult> first = executor.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                return service.chat("b6465a2d", "system", "user one", 100);
            });
            Future<ChatResult> second = executor.submit(() -> {
                start.await(2, TimeUnit.SECONDS);
                return service.chat("b6465a2d", "system", "user two", 100);
            });
            start.countDown();

            assertEquals("ok", first.get(5, TimeUnit.SECONDS).content);
            assertEquals("ok", second.get(5, TimeUnit.SECONDS).content);
            assertEquals(1, maxActive.get());
        } finally {
            executor.shutdownNow();
            server.stop(0);
        }
    }

    private MeetingDeepSeekWebBridgeService service(ModelRegistryStore registry, AiModelProperties props) {
        MeetingDeepSeekWebBridgeService service = new MeetingDeepSeekWebBridgeService();
        ReflectionTestUtils.setField(service, "modelRegistryStore", registry);
        ReflectionTestUtils.setField(service, "aiModelProperties", props);
        return service;
    }

    private MeetingOrchestratorService orchestrator(AiModelRouter router, MeetingDeepSeekWebBridgeService bridgeService) {
        MeetingOrchestratorService orchestrator = new MeetingOrchestratorService();
        ReflectionTestUtils.setField(orchestrator, "aiModelRouter", router);
        ReflectionTestUtils.setField(orchestrator, "meetingDeepSeekWebBridgeService", bridgeService);
        return orchestrator;
    }

    private AiModelProperties props(boolean bridgeEnabled, String defaultModel) {
        AiModelProperties props = new AiModelProperties();
        props.setDefaultModel(defaultModel);
        AiModelProperties.DeepSeekWebBridgeConfig cfg = props.getMeeting().getDeepseekWebBridge();
        cfg.setEnabled(bridgeEnabled);
        cfg.setMode("expert");
        cfg.setSearchMode("fast");
        cfg.setConnectTimeoutSeconds(5);
        cfg.setReadTimeoutSeconds(30);
        cfg.setSerialized(true);
        return props;
    }

    private void pointBridge(MeetingDeepSeekWebBridgeService service, AiModelProperties props, HttpServer server) {
        props.getMeeting().getDeepseekWebBridge()
                .setBridgeUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/search");
        ReflectionTestUtils.setField(service, "aiModelProperties", props);
    }

    private HttpServer bridgeServer(List<String> requestBodies,
                                    String responseBody,
                                    int statusCode,
                                    AtomicInteger active,
                                    AtomicInteger maxActive) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "meeting-bridge-test-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/search", exchange -> {
            int current = active != null ? active.incrementAndGet() : 0;
            if (active != null) {
                maxActive.accumulateAndGet(current, Math::max);
            }
            try {
                requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (active != null) {
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                int code = statusCode > 0 ? statusCode : 200;
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(code, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } finally {
                if (active != null) {
                    active.decrementAndGet();
                }
            }
        });
        server.start();
        return server;
    }

    private ModelEntry entry(String id, String modelId, String apiUrl, String apiFormat, boolean enabled) {
        ModelEntry entry = new ModelEntry();
        entry.setId(id);
        entry.setDisplayName(id);
        entry.setModelId(modelId);
        entry.setApiUrl(apiUrl);
        entry.setApiFormat(apiFormat);
        entry.setEnabled(enabled);
        return entry;
    }
}
