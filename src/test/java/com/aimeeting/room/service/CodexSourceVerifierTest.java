package com.aimeeting.room.service;

import com.aimeeting.room.config.ModelEntry;
import com.aimeeting.room.config.ModelRegistryStore;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The Codex source check accepts a verdict only with a cited link, a dated source and numbers from the quote. */
class CodexSourceVerifierTest {
    private static final String SOURCE = "https://static.cninfo.com.cn/finalpage/2026-10-08/1225595402.PDF";
    private static final String QUOTE = "2026年9月新能源汽车销量463,561辆，同比增长16.98%";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void aClaimTheSourceContradictsCarriesTheSourceStatementQuoteAndLink() {
        JSONObject verdict = CodexSourceVerifier.judge(response(reply("CONTRADICTED",
                "比亚迪2026年9月新能源汽车销量为463,561辆", QUOTE, SOURCE, "2026-10-08"), SOURCE),
                "2026-10-18", lowConfidence());

        assertEquals("L-1", verdict.getString("factId"));
        assertEquals(CodexSourceVerifier.CONTRADICTED, verdict.getString("status"), verdict.toJSONString());
        assertEquals("比亚迪2026年9月新能源汽车销量为463,561辆", verdict.getString("statement"));
        assertEquals(QUOTE, verdict.getString("quote"));
        assertEquals(SOURCE, verdict.getString("sourceUrl"));
        assertEquals("2026-10-08", verdict.getString("publishedAt"));
    }

    @Test
    void aClaimCalledVerifiedWhoseOwnNumbersTheQuoteLacksIsACorrection() {
        JSONObject verdict = CodexSourceVerifier.judge(response(reply("VERIFIED",
                "比亚迪2026年9月新能源汽车销量为463,561辆", QUOTE, SOURCE, "2026-10-08"), SOURCE),
                "2026-10-18", lowConfidence());

        assertEquals(CodexSourceVerifier.CONTRADICTED, verdict.getString("status"), verdict.toJSONString());
        assertEquals("比亚迪2026年9月新能源汽车销量为463,561辆", verdict.getString("statement"));
    }

    @Test
    void aGapAnsweredFromTheSourceIsVerified() {
        JSONObject verdict = CodexSourceVerifier.judge(response(reply("VERIFIED",
                "比亚迪2026年9月新能源汽车销量为463,561辆", QUOTE, SOURCE, "2026-10-08"), SOURCE),
                "2026-10-18", gap());

        assertEquals("G-1", verdict.getString("factId"));
        assertEquals(CodexSourceVerifier.VERIFIED, verdict.getString("status"), verdict.toJSONString());
    }

    @Test
    void aLinkTheSearchNeverReturnedIsNotAccepted() {
        JSONObject verdict = CodexSourceVerifier.judge(response(reply("VERIFIED",
                "比亚迪2026年9月新能源汽车销量为463,561辆", QUOTE, SOURCE, "2026-10-08"),
                "https://example.org/other"), "2026-10-18", lowConfidence());

        assertEquals(CodexSourceVerifier.NOT_FOUND, verdict.getString("status"));
        assertEquals("SOURCE_NOT_CITED", verdict.getString("reason"));
    }

    @Test
    void aSourcePublishedAfterTheMeetingDateIsNotAccepted() {
        JSONObject verdict = CodexSourceVerifier.judge(response(reply("VERIFIED",
                "比亚迪2026年9月新能源汽车销量为463,561辆", QUOTE, SOURCE, "2026-10-08"), SOURCE),
                "2026-10-01", lowConfidence());

        assertEquals(CodexSourceVerifier.NOT_FOUND, verdict.getString("status"));
        assertEquals("SOURCE_AFTER_AS_OF", verdict.getString("reason"));
    }

    @Test
    void aSourceWithoutADateIsNotAccepted() {
        JSONObject verdict = CodexSourceVerifier.judge(response(reply("VERIFIED",
                "比亚迪2026年9月新能源汽车销量为463,561辆", QUOTE, SOURCE, ""), SOURCE),
                "2026-10-18", lowConfidence());

        assertEquals(CodexSourceVerifier.NOT_FOUND, verdict.getString("status"));
        assertEquals("SOURCE_DATE_MISSING", verdict.getString("reason"));
    }

    @Test
    void numbersThatAreNotInTheQuoteAreNotAccepted() {
        JSONObject verdict = CodexSourceVerifier.judge(response(reply("CONTRADICTED",
                "比亚迪2026年9月新能源汽车销量为52万辆", QUOTE, SOURCE, "2026-10-08"), SOURCE),
                "2026-10-18", lowConfidence());

        assertEquals(CodexSourceVerifier.NOT_FOUND, verdict.getString("status"));
        assertEquals("NUMBERS_NOT_IN_QUOTE", verdict.getString("reason"));
    }

    @Test
    void aSourceThatWasNotFoundStaysNotFound() {
        JSONObject verdict = CodexSourceVerifier.judge(response(reply("NOT_FOUND", "", "", "", ""), SOURCE),
                "2026-10-18", lowConfidence());

        assertEquals(CodexSourceVerifier.NOT_FOUND, verdict.getString("status"));
        assertEquals("SOURCE_NOT_FOUND", verdict.getString("reason"));
    }

    @Test
    void anUnreadableReplyMeansTheCheckDidNotHappen() {
        JSONObject verdict = CodexSourceVerifier.judge(response("抱歉，我无法完成。", SOURCE),
                "2026-10-18", lowConfidence());

        assertEquals(CodexSourceVerifier.UNAVAILABLE, verdict.getString("status"));
        assertEquals("CODEX_REPLY_INVALID", verdict.getString("reason"));
    }

    @Test
    void checkAsksTheResponsesEndpointWithWebSearchForOnlySourcesUpToTheMeetingDate() throws Exception {
        FakeCodex codex = new FakeCodex(List.of(response(reply("CONTRADICTED", "比亚迪2026年9月新能源汽车销量为463,561辆",
                QUOTE, SOURCE, "2026-10-08"), SOURCE).toJSONString()));

        JSONObject verdict = verifier(codex.start()).check("2026-10-18", lowConfidence());

        assertEquals("/v1/responses", codex.paths.get(0));
        JSONObject sent = JSON.parseObject(codex.searchBodies.get(0));
        assertEquals("gpt-5.6-luna", sent.getString("model"));
        assertEquals("web_search", sent.getJSONArray("tools").getJSONObject(0).getString("type"));
        assertTrue(sent.getString("input").contains("2026-10-18"), sent.getString("input"));
        assertTrue(sent.getString("input").contains("比亚迪9月新能源车销量约48万辆"), sent.getString("input"));
        assertEquals(CodexSourceVerifier.CONTRADICTED, verdict.getString("status"), verdict.toJSONString());
    }

    @Test
    void aReplyWithoutProofIsAskedOnceMoreWithTheReason() throws Exception {
        FakeCodex codex = new FakeCodex(List.of(
                response(reply("VERIFIED", "比亚迪2026年9月新能源汽车销量为463,561辆", QUOTE, SOURCE, "2026-10-08"),
                        "https://example.org/other").toJSONString(),
                response(reply("VERIFIED", "比亚迪2026年9月新能源汽车销量为463,561辆", QUOTE, SOURCE, "2026-10-08"),
                        SOURCE).toJSONString()));

        JSONObject verdict = verifier(codex.start()).check("2026-10-18", gap());

        assertEquals(2, codex.searchBodies.size());
        String retry = JSON.parseObject(codex.searchBodies.get(1)).getString("input");
        assertTrue(retry.contains("【上一版不合格】"), retry);
        assertTrue(retry.contains("链接不在你打开或引用过的页面里"), retry);
        assertEquals(CodexSourceVerifier.VERIFIED, verdict.getString("status"), verdict.toJSONString());
    }

    @Test
    void aSourceAfterTheMeetingDateOrNoSourceAtAllIsNotAskedAgain() throws Exception {
        FakeCodex codex = new FakeCodex(List.of(response(reply("VERIFIED", "比亚迪2026年9月新能源汽车销量为463,561辆",
                QUOTE, SOURCE, "2026-10-08"), SOURCE).toJSONString()));

        JSONObject verdict = verifier(codex.start()).check("2026-10-01", gap());

        assertEquals(1, codex.searchBodies.size());
        assertEquals("SOURCE_AFTER_AS_OF", verdict.getString("reason"));
    }

    @Test
    void theRequestAsksForAPageThatWasActuallyOpenedAndBothSidesOfAComparison() throws Exception {
        FakeCodex codex = new FakeCodex(List.of(response(reply("NOT_FOUND", "", "", "", ""), SOURCE).toJSONString()));

        verifier(codex.start()).check("2026-10-18", gap());

        String input = JSON.parseObject(codex.searchBodies.get(0)).getString("input");
        assertTrue(input.contains("sourceUrl 必须是你打开过的原文页面"), input);
        assertTrue(input.contains("比较双方的数值和日期"), input);
    }

    @Test
    void aVerdictIsAcceptedOnlyWhenASeparateReadingFindsTheQuoteSupportsTheStatement() throws Exception {
        FakeCodex codex = new FakeCodex(List.of(response(reply("VERIFIED", "比亚迪2026年9月新能源汽车销量为463,561辆",
                QUOTE, SOURCE, "2026-10-08"), SOURCE).toJSONString()));

        JSONObject verdict = verifier(codex.start()).check("2026-10-18", gap());

        assertEquals(CodexSourceVerifier.VERIFIED, verdict.getString("status"), verdict.toJSONString());
        assertEquals(1, codex.supportBodies.size());
        JSONObject sent = JSON.parseObject(codex.supportBodies.get(0));
        assertTrue(sent.get("tools") == null, "the support reading must not search: " + sent);
        String prompt = sent.getJSONArray("messages").toJSONString();
        assertTrue(prompt.contains(QUOTE) && prompt.contains("比亚迪2026年9月新能源汽车销量为463,561辆"), prompt);
    }

    @Test
    void aStatementTheQuoteDoesNotSupportIsAskedAgainWithWhatIsMissingAndThenNotAccepted() throws Exception {
        String overreach = response(reply("VERIFIED", "电池级碳酸锂现货价格较2026年7月末下跌",
                "电池级碳酸锂市场价格区间在99,500-103,500元/吨，较上一个工作日下调1,750元", SOURCE, "2026-10-16"),
                SOURCE).toJSONString();
        FakeCodex codex = new FakeCodex(List.of(overreach));
        codex.supportReplies = List.of(chat("{\"supported\":false,\"missing\":\"7月末的价格\"}"));

        JSONObject verdict = verifier(codex.start()).check("2026-10-18", gap());

        assertEquals(2, codex.searchBodies.size());
        String retry = JSON.parseObject(codex.searchBodies.get(1)).getString("input");
        assertTrue(retry.contains("7月末的价格"), retry);
        assertEquals(CodexSourceVerifier.NOT_FOUND, verdict.getString("status"), verdict.toJSONString());
        assertEquals("QUOTE_DOES_NOT_SUPPORT", verdict.getString("reason"));
    }

    @Test
    void aFailedSupportReadingMeansTheCheckDidNotHappen() throws Exception {
        FakeCodex codex = new FakeCodex(List.of(response(reply("VERIFIED", "比亚迪2026年9月新能源汽车销量为463,561辆",
                QUOTE, SOURCE, "2026-10-08"), SOURCE).toJSONString()));
        codex.supportStatus = 502;

        JSONObject verdict = verifier(codex.start()).check("2026-10-18", gap());

        assertEquals(CodexSourceVerifier.UNAVAILABLE, verdict.getString("status"));
        assertEquals("SUPPORT_CHECK_FAILED", verdict.getString("reason"));
    }

    @Test
    void aFailingCodexApiMeansTheCheckDidNotHappen() throws Exception {
        FakeCodex codex = new FakeCodex(List.of("{\"error\":\"boom\"}"));
        codex.searchStatus = 500;

        JSONObject verdict = verifier(codex.start()).check("2026-10-18", lowConfidence());

        assertEquals(CodexSourceVerifier.UNAVAILABLE, verdict.getString("status"));
        assertEquals("CODEX_HTTP_500", verdict.getString("reason"));
        assertEquals("L-1", verdict.getString("factId"));
    }

    @Test
    void aMissingModelEntryMeansTheCheckDidNotHappen() {
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        CodexSourceVerifier verifier = new CodexSourceVerifier(registry, null, "gpt-5.6-luna");

        JSONObject verdict = verifier.check("2026-10-18", lowConfidence());

        assertEquals(CodexSourceVerifier.UNAVAILABLE, verdict.getString("status"));
        assertEquals("SOURCE_CHECK_MODEL_MISSING", verdict.getString("reason"));
    }

    private CodexSourceVerifier verifier(int port) {
        ModelEntry entry = new ModelEntry();
        entry.setId("406a987f");
        entry.setModelId("gpt-5.6-luna");
        entry.setApiUrl("http://127.0.0.1:" + port + "/v1/chat/completions");
        entry.setApiKey("local-key");
        entry.setApiFormat("openai");
        entry.setEnabled(true);
        ModelRegistryStore registry = mock(ModelRegistryStore.class);
        when(registry.getByModelId("gpt-5.6-luna")).thenReturn(entry);
        return new CodexSourceVerifier(registry, null, "gpt-5.6-luna");
    }

    /**
     * A local stand-in for the Codex API: the Responses endpoint answers with the search replies in turn (the
     * last one repeats), chat completions with the support replies (by default: supported).
     */
    private final class FakeCodex {
        private final List<String> searchReplies;
        private List<String> supportReplies = List.of(chat("{\"supported\":true,\"missing\":\"\"}"));
        private int searchStatus = 200;
        private int supportStatus = 200;
        private final List<String> paths = new java.util.ArrayList<>();
        private final List<String> searchBodies = new java.util.ArrayList<>();
        private final List<String> supportBodies = new java.util.ArrayList<>();

        private FakeCodex(List<String> searchReplies) {
            this.searchReplies = searchReplies;
        }

        private int start() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                paths.add(path);
                boolean search = path.endsWith("/responses");
                List<String> bodies = search ? searchBodies : supportBodies;
                List<String> replies = search ? searchReplies : supportReplies;
                bodies.add(body);
                byte[] bytes = replies.get(Math.min(bodies.size(), replies.size()) - 1).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(search ? searchStatus : supportStatus, bytes.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            });
            server.start();
            return server.getAddress().getPort();
        }
    }

    private static String chat(String content) {
        JSONObject message = new JSONObject(true);
        message.put("role", "assistant");
        message.put("content", content);
        JSONObject choice = new JSONObject(true);
        choice.put("message", message);
        JSONObject body = new JSONObject(true);
        body.put("choices", List.of(choice));
        return body.toJSONString();
    }

    private static JSONObject lowConfidence() {
        JSONObject fact = new JSONObject(true);
        fact.put("id", "L-1");
        fact.put("tier", "L");
        fact.put("question", "比亚迪2026年9月新能源汽车销量为多少辆？");
        fact.put("statement", "比亚迪9月新能源车销量约48万辆");
        fact.put("source", "https://example.org/candidate");
        return fact;
    }

    private static JSONObject gap() {
        JSONObject fact = new JSONObject(true);
        fact.put("id", "G-1");
        fact.put("tier", "G");
        fact.put("question", "比亚迪2026年9月新能源汽车销量为多少辆？");
        fact.put("reason", "N8N_REVIEW_UNVERIFIED");
        return fact;
    }

    private static String reply(String status, String statement, String quote, String url, String publishedAt) {
        JSONObject reply = new JSONObject(true);
        reply.put("status", status);
        reply.put("statement", statement);
        reply.put("quote", quote);
        reply.put("sourceUrl", url);
        reply.put("publishedAt", publishedAt);
        return reply.toJSONString();
    }

    /** A Responses API body: one web search call, then the message whose citation links are the cited sources. */
    private static JSONObject response(String text, String citedUrl) {
        JSONObject search = new JSONObject(true);
        search.put("type", "web_search_call");
        search.put("action", JSON.parseObject("{\"type\":\"search\",\"query\":\"比亚迪 2026年9月 销量\"}"));
        JSONObject citation = new JSONObject(true);
        citation.put("type", "url_citation");
        citation.put("url", citedUrl + "?utm_source=openai");
        JSONObject content = new JSONObject(true);
        content.put("type", "output_text");
        content.put("text", text);
        content.put("annotations", List.of(citation));
        JSONObject message = new JSONObject(true);
        message.put("type", "message");
        message.put("content", List.of(content));
        JSONArray output = new JSONArray();
        output.add(search);
        output.add(message);
        JSONObject response = new JSONObject(true);
        response.put("output", output);
        response.put("usage", JSON.parseObject("{\"input_tokens\":15186,\"output_tokens\":548}"));
        return response;
    }
}
