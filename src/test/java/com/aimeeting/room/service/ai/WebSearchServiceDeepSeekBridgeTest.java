package com.aimeeting.room.service.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchServiceDeepSeekBridgeTest {

    @Test
    void healthAndSearchSendConfiguredFacadeAuthorization() throws Exception {
        AtomicReference<String> healthAuthorization = new AtomicReference<>();
        AtomicReference<String> searchAuthorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> {
            healthAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.createContext("/search", exchange -> {
            searchAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 200,
                    "{\"ok\":true,\"result\":{\"answerText\":\"summary\","
                            + "\"contentStatus\":\"success\",\"links\":[{\"href\":\"https://example.com/a\","
                            + "\"text\":\"source\"}]}}");
        });
        server.start();

        try {
            WebSearchService service = serviceAt(server.getAddress().getPort(), "search-secret");

            assertTrue(service.isDeepSeekWebBridgeReachable());
            List<WebSearchService.SearchResult> results = service.search(
                    "test query", 2, List.of("deepseek-web"), false);

            assertFalse(results.isEmpty());
            assertEquals("Bearer search-secret", healthAuthorization.get());
            assertEquals("Bearer search-secret", searchAuthorization.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void connectionFailureDoesNotExposeBridgeTopologyInSearchMetadata() throws Exception {
        int unusedPort;
        try (ServerSocket unused = new ServerSocket(0)) {
            unusedPort = unused.getLocalPort();
        }
        WebSearchService service = serviceAt(unusedPort, "");
        service.beginSession();

        assertTrue(service.search("test query", 1, List.of("deepseek-web"), false).isEmpty());
        WebSearchService.SearchMeta meta = service.consumeSessionMeta();

        assertNotNull(meta);
        assertNotNull(meta.note);
        assertFalse(meta.note.contains("127.0.0.1"));
        assertFalse(meta.note.contains(String.valueOf(unusedPort)));
    }

    @Test
    void bridgeErrorPayloadDoesNotExposeWorkerDetailsInSearchMetadata() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 503,
                    "{\"ok\":false,\"error\":\"search_failed\","
                            + "\"message\":\"accountKey=technical profileRoot=/private/profile debugPort=9333\"}");
        });
        server.start();

        try {
            WebSearchService service = serviceAt(server.getAddress().getPort(), "");
            service.beginSession();

            assertTrue(service.search("test query", 1, List.of("deepseek-web"), false).isEmpty());
            WebSearchService.SearchMeta meta = service.consumeSessionMeta();

            assertNotNull(meta);
            assertFalse(meta.note.contains("technical"));
            assertFalse(meta.note.contains("profileRoot"));
            assertFalse(meta.note.contains("debugPort"));
            assertFalse(meta.note.contains("9333"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unknownBridgeOutcomeDoesNotRetryThroughDeepSeekAliasesOrScriptFallback() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            requestCount.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 504,
                    "{\"ok\":false,\"error\":\"provider_outcome_unknown\","
                            + "\"providerOutcomeUnknown\":true,\"retrySafe\":false}");
        });
        server.start();

        try {
            WebSearchService service = serviceAt(server.getAddress().getPort(), "");
            ReflectionTestUtils.setField(service, "deepseekWebAllowScriptFallback", true);
            service.beginSession();

            assertTrue(service.search("test query", 1,
                    List.of("deepseek-web", "deepseek_web", "deepseek"), false).isEmpty());
            WebSearchService.SearchMeta meta = service.consumeSessionMeta();

            assertEquals(1, requestCount.get());
            assertEquals("provider_outcome_unknown", meta.status);
            assertTrue(meta.providerDiagnostics.stream()
                    .anyMatch(item -> item.contains("deepseek_web_bridge") && item.contains("不会自动重试")));
            assertFalse(meta.providerDiagnostics.stream()
                    .anyMatch(item -> item.contains("deepseek_web_script")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void scriptLaunchExceptionDoesNotExposeLocalCommandOrPathInMetadata() throws Exception {
        int unusedPort;
        try (ServerSocket unused = new ServerSocket(0)) {
            unusedPort = unused.getLocalPort();
        }
        WebSearchService service = serviceAt(unusedPort, "");
        ReflectionTestUtils.setField(service, "deepseekWebAllowScriptFallback", true);
        ReflectionTestUtils.setField(service, "deepseekWebNodeCommand", "/private/secret/bin/missing-node");
        service.beginSession();

        assertTrue(service.search("test query", 1, List.of("deepseek-web"), false).isEmpty());
        WebSearchService.SearchMeta meta = service.consumeSessionMeta();
        String serialized = String.join("\n", meta.providerDiagnostics);

        assertTrue(serialized.contains("deepseek_web_script"));
        assertFalse(serialized.contains("/private/secret"));
        assertFalse(serialized.contains("missing-node"));
        assertFalse(serialized.contains("127.0.0.1"));
        assertFalse(serialized.contains(String.valueOf(unusedPort)));
    }

    private WebSearchService serviceAt(int port, String apiKey) {
        WebSearchService service = new WebSearchService();
        ReflectionTestUtils.setField(service, "deepseekWebEnabled", true);
        ReflectionTestUtils.setField(service, "deepseekWebBridgeUrl",
                "http://127.0.0.1:" + port + "/search");
        ReflectionTestUtils.setField(service, "deepseekWebApiKey", apiKey);
        ReflectionTestUtils.setField(service, "deepseekWebConnectTimeoutSeconds", 5);
        ReflectionTestUtils.setField(service, "deepseekWebReadTimeoutSeconds", 30);
        ReflectionTestUtils.setField(service, "deepseekWebMode", "fast");
        ReflectionTestUtils.setField(service, "deepseekWebAllowScriptFallback", false);
        return service;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
