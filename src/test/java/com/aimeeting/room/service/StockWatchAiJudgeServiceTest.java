package com.aimeeting.room.service;

import com.aimeeting.room.service.StockWatchAiJudgeService.AiDecision;
import com.aimeeting.room.service.StockWatchAiRuleService.AiWatchRule;
import com.aimeeting.room.service.ai.AiModelRouter;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StockWatchAiJudgeServiceTest {

    @Mock private AiModelRouter aiModelRouter;
    @InjectMocks private StockWatchAiJudgeService service;

    @Test
    void structuredTriggerStillCallsDeepSeekAndKeepsModelAnalysis() {
        AiWatchRule rule = new AiWatchRule();
        rule.setId("rule-1");
        rule.setSymbol("600378");
        rule.setMarket("sh");
        rule.setName("昊华科技");
        rule.setModel("deepseek-chat");
        rule.setSeverity("risk");
        rule.setConditionText("主力净流入或净流出 mainInflow 绝对值 >= 100000000");

        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("mainInflow", -289_580_880.0);
        quote.put("price", 58.34);
        quote.put("changePercent", -1.2);

        when(aiModelRouter.chat(eq("deepseek-chat"), anyString(), anyString(), eq(900)))
                .thenReturn("{\"triggered\":true,\"confidence\":0.91,\"severity\":\"risk\","
                        + "\"shouldNotify\":true,\"reason\":\"资金流出异常\","
                        + "\"analysis\":\"主力资金出现显著净流出，短线承接偏弱。需要继续观察后续成交量是否收缩，以及价格能否快速收复关键均线。\","
                        + "\"matchedSignals\":[],\"missingData\":[]}");

        AiDecision decision = service.judge(
                rule,
                quote,
                ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
        );

        verify(aiModelRouter).chat(eq("deepseek-chat"), anyString(), anyString(), eq(900));
        assertTrue(decision.isTriggered());
        assertTrue(decision.isShouldNotify());
        assertEquals("deepseek-chat", decision.getModel());
        assertTrue(decision.getAnalysis().contains("短线承接偏弱"));
        assertTrue(decision.getReason().contains("主力净流出达到异动阈值"));
        assertFalse(decision.getReason().contains("abs("));
    }

    @Test
    void enhancedContextPromptContainsFactPriority() {
        AiWatchRule rule = new AiWatchRule();
        rule.setId("rule-context");
        rule.setSymbol("002979");
        rule.setMarket("sz");
        rule.setName("雷赛智能");
        rule.setModel("deepseek-chat");
        rule.setSeverity("risk");
        rule.setConditionText("技术面跌破MA20且资金流恶化");

        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("price", 70.99);
        quote.put("changePercent", -3.11);

        Map<String, Object> aiContext = new LinkedHashMap<>();
        aiContext.put("status", "available");
        aiContext.put("currentFacts", Map.of("technicalIndicators", Map.of("ma20", 66.57, "rsi14", 78.06)));
        aiContext.put("recentTechnicalAnalysis", Map.of("title", "高位放量回落", "usage", "historical_background_only_do_not_override_realtime_quote"));

        when(aiModelRouter.chat(eq("deepseek-chat"), anyString(), anyString(), eq(900)))
                .thenReturn("{\"triggered\":false,\"confidence\":0.61,\"severity\":\"risk\","
                        + "\"shouldNotify\":false,\"reason\":\"未确认触发\","
                        + "\"analysis\":\"实时价格仍需优先确认，历史技术结论仅作为背景。\","
                        + "\"matchedSignals\":[],\"missingData\":[]}");

        service.judge(rule, quote, ZonedDateTime.now(ZoneId.of("Asia/Shanghai")), aiContext);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiModelRouter).chat(eq("deepseek-chat"), anyString(), promptCaptor.capture(), eq(900));
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("事实优先级"));
        assertTrue(prompt.contains("本轮quote实时行情"));
        assertTrue(prompt.contains("recentTechnicalAnalysis"));
        assertTrue(prompt.contains("历史记录元数据"));
        assertFalse(prompt.contains("高位放量回落"));
    }

    @Test
    void blankApiFallsBackToBridgeWithExpectedJsonAndRetriesFreshSession() throws Exception {
        AiWatchRule rule = new AiWatchRule();
        rule.setId("rule-bridge");
        rule.setSymbol("600378");
        rule.setMarket("sh");
        rule.setName("昊华科技");
        rule.setModel("deepseek-chat");
        rule.setSeverity("risk");
        rule.setConditionText("量比 volumeRatio >= 2 且涨跌幅绝对值 >= 2%；主力净流入或净流出 mainInflow 绝对值 >= 100000000");

        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("volumeRatio", 2.0);
        quote.put("mainInflow", -261_922_240.0);
        quote.put("price", 66.57);
        quote.put("changePercent", -2.68);

        when(aiModelRouter.chat(eq("deepseek-chat"), anyString(), anyString(), eq(900)))
                .thenReturn("");

        AtomicInteger requestCount = new AtomicInteger();
        List<String> requestBodies = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/search", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(requestBody);
            int attempt = requestCount.incrementAndGet();
            String response = attempt == 1
                    ? "{\"ok\":true,\"result\":{\"answerText\":\"\",\"contentStatus\":\"empty\",\"completionReason\":\"timeout_with_last_state\"}}"
                    : "{\"ok\":true,\"result\":{\"answerText\":\"{\\\"triggered\\\":true,\\\"confidence\\\":0.93,\\\"severity\\\":\\\"risk\\\",\\\"shouldNotify\\\":true,\\\"reason\\\":\\\"触发条件已命中\\\",\\\"analysis\\\":\\\"放量下跌叠加主力资金明显净流出，说明短线抛压占优，需要观察后续是否继续放量破位。\\\",\\\"matchedSignals\\\":[],\\\"missingData\\\":[]}\",\"contentStatus\":\"success\",\"completionReason\":\"complete_json_network\"}}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        try {
            ReflectionTestUtils.setField(service, "bridgeUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/search");
            ReflectionTestUtils.setField(service, "bridgeConnectTimeoutSeconds", 5);
            ReflectionTestUtils.setField(service, "bridgeReadTimeoutSeconds", 30);

            AiDecision decision = service.judge(
                    rule,
                    quote,
                    ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
            );

            assertEquals(2, requestCount.get());
            assertTrue(requestBodies.get(0).contains("\"expectedJson\":true"));
            assertTrue(requestBodies.get(0).contains("\"mode\":\"expert\""));
            assertTrue(requestBodies.get(0).contains("\"forceNewSession\":false"));
            assertTrue(requestBodies.get(1).contains("\"expectedJson\":true"));
            assertTrue(requestBodies.get(1).contains("\"mode\":\"expert\""));
            assertTrue(requestBodies.get(1).contains("\"forceNewSession\":true"));
            assertTrue(decision.isTriggered());
            assertTrue(decision.isShouldNotify());
            assertEquals("deepseek-web", decision.getModel());
            assertTrue(decision.getAnalysis().contains("短线抛压占优"));
            assertTrue(decision.getReason().contains("结构化行情规则命中"));
        } finally {
            server.stop(0);
        }
    }
}
