package com.aimeeting.room.service.ai;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchServiceRecentSearchTest {

    @Test
    void recentSearchRequestsDateWindowAndRejectsOldUndatedAndFutureResults() throws Exception {
        AtomicReference<JSONObject> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            requestBody.set(JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = ("{\"results\":["
                    + "{\"title\":\"recent\",\"content\":\"new finding\",\"url\":\"https://example.com/recent\",\"published_date\":\"Sat, 04 Jul 2026 00:00:00 GMT\"},"
                    + "{\"title\":\"old\",\"content\":\"stale finding\",\"url\":\"https://example.com/old\",\"published_date\":\"2020-01-01\"},"
                    + "{\"title\":\"undated\",\"content\":\"unknown age\",\"url\":\"https://example.com/undated\"},"
                    + "{\"title\":\"future\",\"content\":\"future finding\",\"url\":\"https://example.com/future\",\"published_date\":\"2026-10-05\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            WebSearchService service = new WebSearchService();
            ReflectionTestUtils.setField(service, "tavilyApiKey", "test-key");
            ReflectionTestUtils.setField(service, "tavilyApiUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/search");
            ReflectionTestUtils.setField(service, "tavilySearchDepth", "basic");
            service.beginSession();

            List<WebSearchService.SearchResult> results = service.searchRecent(
                    "ROE site:example.com", 4, LocalDate.of(2026, 7, 4), LocalDate.of(2026, 10, 4));

            assertEquals(1, results.size());
            assertEquals("recent", results.get(0).source);
            assertTrue(results.get(0).snippet.contains("2026-07-04"));
            assertEquals("ROE", requestBody.get().getString("query"));
            assertEquals("2026-07-04", requestBody.get().getString("start_date"));
            assertEquals("2026-10-05", requestBody.get().getString("end_date"));
            assertTrue(requestBody.get().getBooleanValue("filter_by_published_date"));
            assertTrue(requestBody.get().getBooleanValue("include_published_date"));
            assertEquals("example.com", requestBody.get().getJSONArray("include_domains").getString(0));
            assertFalse(service.consumeSessionMeta().provider.isBlank());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recentSearchDoesNotUseUndatedFallbackWhenTavilyIsUnavailable() {
        WebSearchService service = new WebSearchService();
        ReflectionTestUtils.setField(service, "deepseekWebEnabled", true);
        service.beginSession();

        List<WebSearchService.SearchResult> results = service.searchRecent(
                "ROE", 4, LocalDate.of(2026, 7, 4), LocalDate.of(2026, 10, 4));

        assertTrue(results.isEmpty());
        assertEquals("unavailable", service.consumeSessionMeta().status);
    }
}
