package com.aimeeting.room.service;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.StockWatchItemMapper;
import com.aimeeting.room.dto.ChatResult;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.StockWatchItem;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.service.ai.WebSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;

class MeetingDebateServiceEvidenceTraceTest {

    @Test
    void reviewedSearchFailurePausesRoundBeforeModelOpinionAndVoting() {
        MeetingDebateService service = new MeetingDebateService();
        AiMeetingMember member = new AiMeetingMember();
        member.setId(141L);
        member.setMeetingId(62L);
        member.setMemberName("资料分析师");
        member.setModelType("deepseek-chat");
        member.setPersona("公开资料");
        AiMeetingRoom room = new AiMeetingRoom();
        room.setId(62L);
        room.setTitle("联合国宪章签署年份");
        room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
        AiMeetingRoomMapper roomMapper = mock(AiMeetingRoomMapper.class);
        AiMeetingMemberMapper memberMapper = mock(AiMeetingMemberMapper.class);
        AiMeetingOpinionMapper opinionMapper = mock(AiMeetingOpinionMapper.class);
        MeetingWorkflowService workflow = mock(MeetingWorkflowService.class);
        MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        com.aimeeting.room.config.ModelRegistryStore registry = mock(com.aimeeting.room.config.ModelRegistryStore.class);
        when(memberMapper.selectByMeetingId(62L)).thenReturn(List.of(member));
        when(workflow.resolveMembersForCurrentStage(eq(62L), anyList())).thenReturn(List.of(member));
        when(roomMapper.selectById(62L)).thenReturn(room);
        when(opinionMapper.selectByMeetingIdAndRound(62L, 1)).thenReturn(Collections.emptyList());
        when(reviewed.isEnabled()).thenReturn(true);
        when(orchestrator.buildReviewedEvidenceNeedsPrompt(62L, 141L, 1)).thenReturn("member-plan");
        when(orchestrator.callModelStrict(eq("deepseek-chat"), anyString(), eq("member-plan"), eq(500)))
                .thenReturn("{\"need\":true,\"needs\":[{\"id\":\"year\",\"question\":\"联合国宪章签署年份是哪年？\",\"eastmoneyFields\":[],\"webSearch\":true,\"sourceFocus\":[]}]}");
        when(reviewed.search(anyString())).thenThrow(new MeetingReviewedEvidenceService.EvidenceRejected("N8N_REVIEW_UNVERIFIED"));
        ReflectionTestUtils.setField(service, "roomMapper", roomMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "opinionMapper", opinionMapper);
        ReflectionTestUtils.setField(service, "workflowService", workflow);
        ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
        ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
        ReflectionTestUtils.setField(service, "modelRegistryStore", registry);

        assertEquals(0, service.executeDebateRound(62L, 1).size());
        verify(reviewed).search(argThat(query -> query.contains("本次唯一待核验缺口：联合国宪章签署年份是哪年？")
                && !query.contains("优先给出近期事件")));
        verify(roomMapper).updateStatus(62L, MeetingStatusEnum.PAUSED.getCode());
        verify(workflow).markReviewedEvidenceBlocked(eq(62L), anyString());
        verify(opinionMapper, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(orchestrator, never()).callModelExStrict(anyString(), anyString(), anyString());
    }

    @Test
    void strictGroundingRetryPreservesUnknownOutcomeBlock() {
        MeetingDebateService service = new MeetingDebateService();
        MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
        ChatResult unknown = new ChatResult(
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                "deepseek_web_bridge",
                "provider_outcome_unknown",
                "不会自动重试",
                "provider_outcome_unknown",
                "不会自动重试",
                true,
                false
        );
        when(orchestrator.callModelEx(eq("deepseek-chat"), anyString(), anyString())).thenReturn(unknown);

        ChatResult result = ReflectionTestUtils.invokeMethod(
                service,
                "retryWithStrictEvidenceGrounding",
                "system",
                "context",
                "deepseek-chat",
                Collections.emptyList(),
                Collections.emptyList(),
                new ChatResult("previous")
        );

        assertNotNull(result);
        assertTrue(result.blocksAutomaticRetry());
    }

    @Test
    void extractsStructuredSourceTraceFromEastmoneySnippet() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{"
                + "\"dataQuality\":{"
                + "\"canPredict\":true,"
                + "\"requiredMissing\":[],"
                + "\"optionalMissing\":[\"latestNews:empty\"],"
                + "\"sourceStatus\":{"
                + "\"quote\":{\"ok\":true,\"sourceType\":\"INTERNAL_SERVICE\",\"durationMs\":3,\"count\":1},"
                + "\"financials\":{\"ok\":true,\"sourceType\":\"HTTP_JSON\",\"durationMs\":8,\"count\":4,"
                + "\"requestCount\":1,\"lastUrl\":\"https://datacenter-web.eastmoney.com/api/data/v1/get\","
                + "\"lastParamsHash\":\"sha256:params\",\"lastResponseHash\":\"sha256:response\"}"
                + "}}}";

        JSONObject trace = ReflectionTestUtils.invokeMethod(service, "buildStructuredDataSourceTrace", result);

        assertNotNull(trace);
        assertEquals(true, trace.getBoolean("canPredict"));
        assertEquals(2, trace.getJSONArray("sources").size());
        JSONObject financials = trace.getJSONArray("sources").stream()
                .map(JSONObject.class::cast)
                .filter(item -> "financials".equals(item.getString("name")))
                .findFirst()
                .orElseThrow();
        assertEquals("financials", financials.getString("name"));
        assertEquals("HTTP_JSON", financials.getString("sourceType"));
        assertEquals("sha256:response", financials.getString("lastResponseHash"));
    }

    @Test
    void claimAuditFlagsNumbersMissingFromProviderEvidence() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz quote";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{\"quote\":{\"price\":24.80,\"changePercent\":2.10}}";

        ChatResult chatResult = new ChatResult("雷赛智能最新价 24.80元，ROE 18.5%，趋势偏强。");

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                null
        );

        assertNotNull(audit);
        assertEquals("unsupported_claims", audit.getString("status"));
        assertEquals(2, audit.getIntValue("numericClaimCount"));
        assertEquals(1, audit.getJSONArray("unsupportedNumericClaims").size());
        assertEquals("18.5%", audit.getJSONArray("unsupportedNumericClaims").getJSONObject(0).getString("claim"));
    }

    @Test
    void claimAuditAcceptsScientificAmountsAndAllSuppliedFundFlowFields() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = structuredResult("东方财富结构化数据 300750.sz quote",
                "{\"quote\":{\"price\":285.27,\"previousClose\":291.11,\"open\":292.0,"
                + "\"high\":293.47,\"low\":285.17,\"amount\":9.30697261057E9,"
                + "\"mainInflow\":-3.75513504E8,\"mainInflowRatio\":-4.03,"
                + "\"superLargeInflow\":-6.013768E7,\"largeInflow\":-3.15375824E8,"
                + "\"mediumInflow\":3.39201328E8,\"smallInflow\":3.6312192E7}}");
        JSONObject audit = ReflectionTestUtils.invokeMethod(service, "buildClaimAudit", new ChatResult(
                "成交额9.30697261057E9元，主力净流入-3.75513504E8元，超大单-6.013768E7元，"
                + "大单-3.15375824E8元，中单3.39201328E8元，小单3.6312192E7元；"
                + "昨收291.11元，今开292元，高293.47元，低285.17元。"), List.of(result), newSearchPlan("300750"));
        assertEquals("ok", audit.getString("status"));
        assertEquals(10, audit.getIntValue("numericClaimCount"));

        JSONObject invalid = ReflectionTestUtils.invokeMethod(service, "buildClaimAudit", new ChatResult(
                "金额7元，资金比例4.03%，主力净流入3.75513504E8元，金额9.30697261057E8元，金额1E10元。"),
                List.of(result), newSearchPlan("300750"));
        assertEquals(5, invalid.getIntValue("unsupportedNumericClaimCount"));
    }

    @Test
    void claimAuditAllowsOnlyDisplayPrecisionRoundingWithSameSignAndUnits() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = structuredResult("东方财富结构化数据 300750.sz quote",
                "{\"quote\":{\"amount\":9.30697261057E9,\"mainInflow\":-3.75513504E8}}");
        JSONObject audit = ReflectionTestUtils.invokeMethod(service, "buildClaimAudit", new ChatResult(
                "成交额约93.07亿元，主力净流入约-3.76亿元。"), List.of(result), newSearchPlan("300750"));
        assertEquals("ok", audit.getString("status"));
        JSONObject invalid = ReflectionTestUtils.invokeMethod(service, "buildClaimAudit", new ChatResult(
                "成交额93.06亿元，净流入3.76亿元，比率93.07%。"), List.of(result), newSearchPlan("300750"));
        assertEquals(3, invalid.getIntValue("unsupportedNumericClaimCount"));
    }

    @Test
    void claimAuditUsesSuppliedHistoricalFinancialRowsButRejectsNewDerivatives() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = structuredResult("东方财富结构化数据 300750.sz financials",
                "{\"financials\":[{\"reportDate\":\"2026-06-30\",\"revenue\":276917000000,"
                + "\"netProfit\":43284000000,\"netMarginPct\":16.9843},"
                + "{\"reportDate\":\"2026-03-31\",\"revenue\":129131000000,"
                + "\"netProfit\":20738000000,\"netMarginPct\":17.61},"
                + "{\"reportDate\":\"2025-12-31\",\"revenue\":423702000000,\"netMarginPct\":18.12}]}");
        JSONObject audit = ReflectionTestUtils.invokeMethod(service, "buildClaimAudit", new ChatResult(
                "中报净利率16.98%，一季报营收1291.31亿元、归母净利润207.38亿元、净利率17.61%；"
                + "上年营收4237.02亿元、净利率18.12%。"), List.of(result), newSearchPlan("300750"));
        assertEquals("ok", audit.getString("status"));
        JSONObject invalid = ReflectionTestUtils.invokeMethod(service, "buildClaimAudit", new ChatResult(
                "一季报净利率17.62%；推算二季度营收1477.86亿元，这是派生计算。"),
                List.of(result), newSearchPlan("300750"));
        assertEquals(2, invalid.getIntValue("unsupportedNumericClaimCount"));
    }

    @Test
    void claimAuditUsesSuppliedDailyKlinePricesAmountsAndPercentages() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = structuredResult("东方财富结构化数据 300750.sz dailyKline",
                "{\"dailyKline\":[{\"date\":\"2026-10-08\",\"open\":292.00,\"high\":293.47,"
                + "\"low\":285.17,\"close\":287.06,\"change\":-4.05,\"amount\":10230000000,"
                + "\"changePct\":-1.39,\"amplitudePct\":2.85,\"turnoverPct\":0.84},"
                + "{\"date\":\"2026-09-30\",\"close\":291.11,\"changePct\":1.50}]}");
        JSONObject audit = ReflectionTestUtils.invokeMethod(service, "buildClaimAudit", new ChatResult(
                "当日开盘292元，最高293.47元，最低285.17元，收盘287.06元，涨跌额-4.05元，"
                + "成交额102.3亿元，涨跌幅-1.39%，振幅2.85%，换手率0.84%；"
                + "上一交易日收盘291.11元，涨跌幅1.50%。"), List.of(result), newSearchPlan("300750"));
        assertEquals("ok", audit.getString("status"));
        JSONObject invalid = ReflectionTestUtils.invokeMethod(service, "buildClaimAudit", new ChatResult(
                "涨跌幅-1.38%，振幅2.86%，换手率0.85%，收盘287.07元。"),
                List.of(result), newSearchPlan("300750"));
        assertEquals(4, invalid.getIntValue("unsupportedNumericClaimCount"));
    }

    @Test
    void guardedTermAuditAllowsExplicitOmissionButStillChecksEveryAssertion() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = structuredResult("东方财富结构化数据 300750.sz quote",
                "{\"quote\":{\"price\":285.27}}");
        for (String omission : List.of("机构一致预期本轮证据未提供/无法核验。", "本轮未提供机构一致预期。",
                "估值、当前市价、自由现金流、资本开支、有息负债结构、机构一致预期、未来季度预测均“本轮证据未提供/无法核验”。")) {
            JSONObject audit = ReflectionTestUtils.invokeMethod(service, "buildClaimAudit",
                    new ChatResult(omission), List.of(result), newSearchPlan("300750"));
            assertEquals("ok", audit.getString("status"), omission);
        }
        for (String assertion : List.of("机构一致预期上修。本轮未提供机构一致预期。",
                "机构一致预期上修，但本轮证据未提供/无法核验。",
                "机构一致预期上修本轮证据未提供/无法核验。",
                "机构一致预期本轮证据未提供/无法核验，但一致预期仍然上修。")) {
            JSONObject audit = ReflectionTestUtils.invokeMethod(service, "buildClaimAudit",
                    new ChatResult(assertion), List.of(result), newSearchPlan("300750"));
            assertEquals(1, audit.getIntValue("guardedTermViolationCount"), assertion);
        }
    }

    @Test
    void structuredProjectionsSharingQuoteUrlRemainDistinctButExactCopiesCollapse() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult quote = structuredResult("东方财富结构化数据 300750.sz quote",
                "{\"quote\":{\"price\":285.27}}");
        WebSearchService.SearchResult history = structuredResult("东方财富结构化数据 300750.sz dailyKline",
                "{\"dailyKline\":[{\"date\":\"2026-10-08\",\"close\":285.27}]}");
        WebSearchService.SearchResult indicators = structuredResult("东方财富结构化数据 300750.sz technicalIndicators",
                "{\"technicalIndicators\":{\"rsi14\":41.2}}");
        for (WebSearchService.SearchResult result : List.of(quote, history, indicators)) {
            result.url = "https://quote.eastmoney.com/sz300750.html";
            result.withTrace("eastmoney", "STRUCTURED_DATA", result.source, result.snippet);
        }
        List<WebSearchService.SearchResult> retained = ReflectionTestUtils.invokeMethod(service,
                "dedupeSearchResults", List.of(quote, history, indicators, quote));
        assertEquals(List.of(quote, history, indicators), retained);
    }

    @Test
    void claimAuditDoesNotTreatProvisionalNumbersAsVerifiedFacts() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult provisional = new WebSearchService.SearchResult();
        provisional.source = "n8n 待核验候选：利润率";
        provisional.snippet = "【待核验】利润率为18.5%";
        provisional.url = "https://example.org/candidate";
        provisional.withTrace("n8n-deepseek-provisional", "LOW_CONFIDENCE_SEARCH",
                "利润率是多少？", "candidate");
        WebSearchService.SearchResult verified = structuredResult(
                "东方财富结构化数据 300750.sz quote",
                "{\"symbol\":\"300750\",\"quote\":{\"price\":291.11}}");

        JSONObject audit = ReflectionTestUtils.invokeMethod(service, "buildClaimAudit",
                new ChatResult("利润率已达到18.5%。"), List.of(verified, provisional),
                newSearchPlan("300750"));

        assertNotNull(audit);
        assertEquals("unsupported_claims", audit.getString("status"));
        assertEquals("18.5%", audit.getJSONArray("unsupportedNumericClaims")
                .getJSONObject(0).getString("claim"));
    }

    @Test
    void claimAuditChecksPublishedReplyInsteadOfModelEvidencePreamble() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = structuredResult(
                "东方财富结构化数据 300750.sz quote",
                "{\"symbol\":\"300750\",\"market\":\"sz\",\"quote\":{\"price\":291.11}}"
        );
        ChatResult chatResult = new ChatResult(
                "{\"e\":[{\"p\":\"模型自述股息率 9.9%\"}]}\n==REPLY==\n本轮证据显示最新价 291.11元，后续走势仍需观察。"
        );

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service, "buildClaimAudit", chatResult, List.of(result), newSearchPlan("300750")
        );

        assertNotNull(audit);
        assertEquals("ok", audit.getString("status"));
        assertEquals(1, audit.getIntValue("numericClaimCount"));
    }

    @Test
    void claimAuditRejectsEvidenceJsonWithoutReplyBody() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = structuredResult(
                "东方财富结构化数据 300750.sz quote",
                "{\"symbol\":\"300750\",\"quote\":{\"price\":291.11}}"
        );

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service, "buildClaimAudit", new ChatResult("{\"q\":[],\"e\":[]}"),
                List.of(result), newSearchPlan("300750")
        );

        assertNotNull(audit);
        assertEquals("unsupported_claims", audit.getString("status"));
        assertEquals(1, audit.getIntValue("formatViolationCount"));
    }

    @Test
    void structuredSnippetFitsBudgetAsValidJsonWithoutLosingQuoteOrRecentKline() {
        MeetingDebateService service = new MeetingDebateService();
        JSONObject payload = new JSONObject(true);
        payload.put("symbol", "300750");
        payload.put("market", "sz");
        payload.put("quote", JSONObject.parseObject("{\"price\":291.11,\"changePercent\":1.5}"));
        JSONArray klines = new JSONArray();
        for (int i = 0; i < 40; i++) {
            JSONObject day = new JSONObject(true);
            day.put("date", String.format("2026-09-%02d", i + 1));
            day.put("close", 290 + i);
            day.put("detail", "行情明细".repeat(30));
            klines.add(day);
        }
        payload.put("dailyKline", klines);

        String snippet = ReflectionTestUtils.invokeMethod(
                service, "serializeStructuredSearchSnippet", payload, 7000
        );

        assertNotNull(snippet);
        assertTrue(snippet.length() <= 7000);
        JSONObject parsed = JSONObject.parseObject(snippet);
        assertEquals("300750", parsed.getString("symbol"));
        assertEquals(291.11, parsed.getJSONObject("quote").getDoubleValue("price"));
        assertEquals(329, parsed.getJSONArray("dailyKline").getJSONObject(
                parsed.getJSONArray("dailyKline").size() - 1).getIntValue("close"));
    }

    @Test
    void fallbackDoesNotExposeCutOffStructuredJsonAsProse() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        AiMeetingMember member = new AiMeetingMember();
        member.setMemberName("股票深度价值派分析师");
        WebSearchService.SearchResult result = structuredResult(
                "东方财富结构化数据 300750.sz quote",
                "{\"symbol\":\"300750\",\"quote\":{\"price\":291.11},\"dailyKline\":[{\"date\":\"2026-09-30\"…"
        );

        String content = ReflectionTestUtils.invokeMethod(
                service, "synthesizeExternalSearchFallbackOpinion", member,
                newSearchPlan("300750"), List.of(result), new ChatResult("未核验数字")
        );

        assertNotNull(content);
        assertTrue(content.contains("结构化行情"));
        assertFalse(content.contains("{\"symbol\""));
        assertFalse(content.contains("\"dailyKline\""));
    }

    @Test
    void rejectedDeepSeekReplyRemainsVisibleOnlyAsUnverifiedEvidence() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
        WebSearchService.SearchResult result = structuredResult(
                "东方财富结构化数据 300750.sz quote",
                "{\"symbol\":\"300750\",\"market\":\"sz\",\"quote\":{\"price\":291.11}}"
        );
        ChatResult first = new ChatResult("初稿：股息率 9.9%。");
        ChatResult revision = new ChatResult("{\"e\":[]}\n==REPLY==\n复核稿：预计上涨 88%，仍需观察。");
        when(orchestrator.callModelEx(eq("deepseek-chat"), anyString(), anyString())).thenReturn(revision);
        AiMeetingMember member = new AiMeetingMember();
        member.setMemberName("股票深度价值派分析师");

        ChatResult stored = ReflectionTestUtils.invokeMethod(
                service, "enforceSearchEvidenceGrounding", member, "system", "context", "deepseek-chat",
                List.of("宁德时代行情"), List.of(result), newSearchPlan("300750"), first
        );
        assertNotNull(stored);
        assertEquals("strict_grounding_fallback", stored.searchStatus);
        assertFalse(stored.content.contains("预计上涨 88%"));
        String evidenceJson = ReflectionTestUtils.invokeMethod(
                service, "buildEvidenceJson", null, null, 1, null,
                List.of("宁德时代行情"), List.of(result), newSearchPlan("300750"),
                stored, null, null, null
        );
        JSONObject evidence = JSONObject.parseObject(evidenceJson);
        assertEquals("复核稿：预计上涨 88%，仍需观察。", evidence.getString("unverifiedModelReply"));
        assertEquals("unsupported_claims", evidence.getJSONObject("rejectedClaimAudit").getString("status"));
        assertEquals("ok", evidence.getJSONObject("claimAudit").getString("status"));
    }

    @Test
    void evidenceOnlyModelReplyStoresFormatFailureWithoutPublishingJson() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
        WebSearchService.SearchResult result = structuredResult(
                "东方财富结构化数据 300750.sz quote",
                "{\"symbol\":\"300750\",\"quote\":{\"price\":291.11}}"
        );
        ChatResult onlyEvidence = new ChatResult("{\"q\":[],\"e\":[]}");
        when(orchestrator.callModelEx(eq("deepseek-chat"), anyString(), anyString())).thenReturn(onlyEvidence);

        ChatResult stored = ReflectionTestUtils.invokeMethod(
                service, "enforceSearchEvidenceGrounding", new AiMeetingMember(), "system", "context",
                "deepseek-chat", List.of(), List.of(result), newSearchPlan("300750"), onlyEvidence
        );
        assertEquals("strict_grounding_fallback", stored.searchStatus);
        assertFalse(stored.content.contains("\"q\""));
        String evidenceJson = ReflectionTestUtils.invokeMethod(
                service, "buildEvidenceJson", null, null, 1, null,
                List.of(), List.of(result), newSearchPlan("300750"), stored, null, null, null
        );
        JSONObject evidence = JSONObject.parseObject(evidenceJson);
        assertFalse(evidence.containsKey("unverifiedModelReply"));
        assertEquals(1, evidence.getJSONObject("rejectedClaimAudit").getIntValue("formatViolationCount"));
    }

    @Test
    void guardedTermAuditDoesNotMatchEnglishTermsInsideOtherWords() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz quote";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{\"quote\":{\"price\":24.80}}";

        ChatResult chatResult = new ChatResult(
                "DeepSeek completion 已返回技术指标讨论，后续 steps 是量价共振和纪律执行。"
        );

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                newSearchPlan("002979")
        );

        assertNotNull(audit);
        assertEquals("ok", audit.getString("status"));
        assertEquals(0, audit.getIntValue("guardedTermViolationCount"));
    }

    @Test
    void guardedTermAuditStillFlagsStandaloneEnglishFinancialTerms() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz quote";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{\"quote\":{\"price\":24.80}}";

        ChatResult chatResult = new ChatResult(
                "本轮直接引用 EPS、ROE 和 Beta 来判断估值与风险。"
        );

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                newSearchPlan("002979")
        );

        assertNotNull(audit);
        assertEquals("unsupported_claims", audit.getString("status"));
        JSONArray violations = audit.getJSONArray("guardedTermViolations");
        assertEquals(3, violations.size());
        assertTrue(violations.stream().map(JSONObject.class::cast).anyMatch(item -> "eps".equals(item.getString("term"))));
        assertTrue(violations.stream().map(JSONObject.class::cast).anyMatch(item -> "roe".equals(item.getString("term"))));
        assertTrue(violations.stream().map(JSONObject.class::cast).anyMatch(item -> "beta".equals(item.getString("term"))));
    }

    @Test
    void guardedTermAuditAcceptsStandaloneEnglishTermsWhenEvidenceContainsThem() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{\"financials\":[{\"eps\":0.58}],\"stockDetail\":{\"roe\":18.5},\"risk\":{\"beta\":1.2}}";

        ChatResult chatResult = new ChatResult(
                "本轮证据给出 EPS、ROE 和 Beta，可作为待核验指标引用。"
        );

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                newSearchPlan("002979")
        );

        assertNotNull(audit);
        assertEquals("ok", audit.getString("status"));
        assertEquals(0, audit.getIntValue("guardedTermViolationCount"));
    }

    @Test
    void factCatalogExtractsStructuredEastmoneyFields() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.rawHash = "sha256:testhash";
        result.snippet = "{"
                + "\"quote\":{\"price\":24.80,\"changePercent\":2.10},"
                + "\"stockDetail\":{\"roe\":18.5,\"dynamicPe\":32.4},"
                + "\"financials\":[{\"eps\":4.58,\"roePct\":17.9,\"netProfitYoyPct\":35.5}],"
                + "\"shareholderCounts\":[{\"holderNum\":12345,\"holderNumRatioPct\":-2.3}],"
                + "\"technicalIndicators\":{\"ma20\":23.6},"
                + "\"marketBenchmark\":{\"relativeStrength20Pct\":5.2}"
                + "}";

        JSONObject catalog = ReflectionTestUtils.invokeMethod(service, "buildFactCatalog", List.of(result));

        assertNotNull(catalog);
        JSONArray facts = catalog.getJSONArray("facts");
        assertNotNull(facts);
        assertTrue(facts.size() >= 8);
        JSONObject eps = findFact(facts, "最新财报EPS");
        assertEquals("financials[0].eps", eps.getString("path"));
        assertEquals("4.58元", eps.getString("rawValue"));
        assertEquals("sha256:testhash", eps.getString("rawHash"));
        JSONObject roe = findFact(facts, "最新ROE");
        assertEquals("stockDetail.roe", roe.getString("path"));
        assertEquals("18.5%", roe.getString("rawValue"));
        assertEquals("STRUCTURED_DATA", roe.getString("sourceType"));
        JSONObject holders = findFact(facts, "最新股东户数");
        assertEquals("shareholderCounts[0].holderNum", holders.getString("path"));
        assertEquals("12345户", holders.getString("rawValue"));
    }

    @Test
    void claimAuditAcceptsEquivalentStructuredFactNumbersWithSameUnit() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{\"quote\":{\"price\":24.8},\"stockDetail\":{\"roe\":18.5}}";

        ChatResult chatResult = new ChatResult("雷赛智能最新价 24.80元，最新ROE 18.50%，趋势偏强。");

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                null
        );

        assertNotNull(audit);
        assertEquals("ok", audit.getString("status"));
        assertEquals(2, audit.getIntValue("numericClaimCount"));
        assertEquals(0, audit.getJSONArray("unsupportedNumericClaims").size());
    }

    @Test
    void claimAuditDoesNotAcceptSameNumberWithDifferentUnit() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{\"quote\":{\"price\":18.5}}";

        ChatResult chatResult = new ChatResult("雷赛智能ROE 18.5%，趋势偏强。");

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                null
        );

        assertNotNull(audit);
        assertEquals("unsupported_claims", audit.getString("status"));
        assertEquals(1, audit.getJSONArray("unsupportedNumericClaims").size());
        assertEquals("18.5%", audit.getJSONArray("unsupportedNumericClaims").getJSONObject(0).getString("claim"));
    }

    @Test
    void claimAuditDoesNotAcceptBareNumberWithoutClaimUnit() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "WEB";
        result.snippet = "雷赛智能 ROE: 18.5";

        ChatResult chatResult = new ChatResult("雷赛智能ROE 18.5%，趋势偏强。");

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                null
        );

        assertNotNull(audit);
        assertEquals("unsupported_claims", audit.getString("status"));
        assertEquals(1, audit.getJSONArray("unsupportedNumericClaims").size());
        assertEquals("18.5%", audit.getJSONArray("unsupportedNumericClaims").getJSONObject(0).getString("claim"));
    }

    @Test
    void buildEvidenceJsonStoresFactCatalogAndPerResultFacts() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.provider = "eastmoney";
        result.rawHash = "sha256:bundlehash";
        result.snippet = "{"
                + "\"quote\":{\"price\":24.80,\"changePercent\":2.10},"
                + "\"valuation\":{\"peTtm\":42.6,\"pbMrq\":5.1},"
                + "\"financials\":[{\"eps\":4.58}],"
                + "\"shareholderCounts\":[{\"holderNum\":\"12,345\"}]"
                + "}";

        String evidenceJson = ReflectionTestUtils.invokeMethod(
                service,
                "buildEvidenceJson",
                null,
                null,
                1,
                null,
                List.of("雷赛智能 估值 财报"),
                List.of(result),
                null,
                null,
                null,
                null,
                null
        );

        assertNotNull(evidenceJson);
        JSONObject evidence = JSONObject.parseObject(evidenceJson);
        assertNotNull(evidence.getJSONObject("factCatalog"));
        JSONArray catalogFacts = evidence.getJSONObject("factCatalog").getJSONArray("facts");
        assertEquals("valuation.peTtm", findFact(catalogFacts, "PE_TTM").getString("path"));
        assertEquals("42.6倍", findFact(catalogFacts, "PE_TTM").getString("rawValue"));

        JSONObject storedResult = evidence.getJSONArray("results").getJSONObject(0);
        JSONArray resultFacts = storedResult.getJSONArray("facts");
        assertNotNull(resultFacts);
        assertEquals("sha256:bundlehash", findFact(resultFacts, "最新价").getString("rawHash"));
        assertEquals("12345户", findFact(resultFacts, "最新股东户数").getString("rawValue"));
    }

    @Test
    void strictGroundingFallbackEvidenceDedupesQueriesAndResultsAndMarksDegraded() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = structuredResult(
                "东方财富结构化数据 002979.sz",
                "{\"symbol\":\"002979\",\"market\":\"sz\",\"quote\":{\"price\":24.80}}"
        );
        result.url = "https://quote.eastmoney.com/sz002979.html";
        result.provider = "eastmoney";
        result.rawHash = "sha256:same";
        ChatResult fallback = new ChatResult(
                "以下判断基于本轮联网搜索结果整理。",
                List.of("雷赛智能 技术指标 最新"),
                List.of(result),
                "external_search",
                "strict_grounding_fallback",
                "检测到正文包含未在本轮证据中出现的股票/量化/财务主张，已降级。"
        );

        String evidenceJson = ReflectionTestUtils.invokeMethod(
                service,
                "buildEvidenceJson",
                null,
                null,
                1,
                null,
                Arrays.asList("雷赛智能 技术指标 最新", "雷赛智能 技术指标 最新"),
                Arrays.asList(result, result),
                newSearchPlan("002979"),
                fallback,
                null,
                null,
                null
        );

        assertNotNull(evidenceJson);
        JSONObject evidence = JSONObject.parseObject(evidenceJson);
        assertEquals(1, evidence.getJSONArray("q").size());
        assertEquals(1, evidence.getJSONArray("results").size());
        JSONObject searchMeta = evidence.getJSONObject("searchMeta");
        assertNotNull(searchMeta);
        assertEquals("strict_grounding_fallback", searchMeta.getString("generationStatus"));
        assertEquals(true, searchMeta.getBoolean("generationDegraded"));
        assertEquals(true, searchMeta.getBoolean("degraded"));
    }

    @Test
    void strictFallbackOpinionSummarizesStructuredDataWithoutRawJson() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        AiMeetingMember member = new AiMeetingMember();
        member.setMemberName("股票技术趋势分析师");
        member.setPersona("技术面分析");
        WebSearchService.SearchResult result = structuredResult(
                "东方财富结构化数据 002979.sz",
                "{\"symbol\":\"002979\",\"market\":\"sz\",\"quote\":{\"price\":24.80,\"changePercent\":2.1},\"technicalIndicators\":{\"ma20\":23.6,\"rsi6\":61.2}}"
        );

        String content = ReflectionTestUtils.invokeMethod(
                service,
                "synthesizeExternalSearchFallbackOpinion",
                member,
                newSearchPlan("002979"),
                List.of(result),
                new ChatResult("原始正文返回了不受支持的数字")
        );

        assertNotNull(content);
        assertTrue(content.contains("结构化行情摘要"));
        assertTrue(content.contains("最新价 24.8元"));
        assertTrue(content.contains("MA20 23.6"));
        assertFalse(content.contains("23.6null"));
        assertFalse(content.contains("\"quote\""));
        assertFalse(content.contains("{\"symbol\""));
    }

    @Test
    void strictFallbackOpinionHandlesMeetingStyleAnchorFactsWithoutRawJson() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        AiMeetingMember member = new AiMeetingMember();
        member.setMemberName("股票技术趋势分析师");
        member.setPersona("技术面分析");
        WebSearchService.SearchResult result = structuredResult(
                "东方财富结构化数据 601689.sh quote,anchorFacts,technicalIndicators",
                "{\"symbol\":\"601689\",\"market\":\"sh\",\"quote\":{\"price\":62.43,\"changePercent\":10.01},"
                        + "\"anchorFacts\":{\"technicalIndicators\":{\"ma20\":60.215,\"rsi14\":53.52,\"macdDif\":-1.9007,\"priceVsMa20Pct\":3.6785}},"
                        + "\"technicalIndicators\":{\"$ref\":\"$.anchorFacts.technicalIndicators\"}}"
        );

        String content = ReflectionTestUtils.invokeMethod(
                service,
                "synthesizeExternalSearchFallbackOpinion",
                member,
                newSearchPlan("601689"),
                List.of(result),
                new ChatResult(
                        "原始正文返回了不受支持的数字",
                        Collections.emptyList(),
                        Collections.emptyList(),
                        "model_only",
                        "strict_grounding_fallback",
                        "证据审计未通过。",
                        "success",
                        "主模型已返回稳定正文。"
                )
        );

        assertNotNull(content);
        assertTrue(content.contains("结构化行情摘要"));
        assertTrue(content.contains("MA20 60.215"));
        assertTrue(content.contains("RSI14 53.52"));
        assertTrue(content.contains("主模型正文未通过事实边界审计"));
        assertFalse(content.contains("主模型本轮未返回稳定答案"));
        assertFalse(content.contains("\"anchorFacts\""));
        assertFalse(content.contains("{\"symbol\""));
    }

    @Test
    void mergeSearchEvidenceDedupesStrictFallbackCarriedResults() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = structuredResult(
                "东方财富结构化数据 002979.sz",
                "{\"symbol\":\"002979\",\"market\":\"sz\",\"quote\":{\"price\":24.80}}"
        );
        result.url = "https://quote.eastmoney.com/sz002979.html";

        List<String> queries = ReflectionTestUtils.invokeMethod(
                service,
                "mergeSearchQueries",
                List.of("雷赛智能 技术指标 最新"),
                List.of("雷赛智能 技术指标 最新")
        );
        List<WebSearchService.SearchResult> results = ReflectionTestUtils.invokeMethod(
                service,
                "mergeSearchResults",
                List.of(result),
                List.of(result)
        );

        assertNotNull(queries);
        assertNotNull(results);
        assertEquals(1, queries.size());
        assertEquals(1, results.size());
    }

    @Test
    void technicalIndicatorResearchIntentDoesNotBecomeForecastOutlook() {
        MeetingDebateService service = new MeetingDebateService();
        String title = "找到真正有价值的股票技术面指标，防止被某些技术指标误导";

        String intent = ReflectionTestUtils.invokeMethod(
                service,
                "inferIntentFromTitleAndPersona",
                title,
                "量化多因子分析师"
        );
        List<String> queries = ReflectionTestUtils.invokeMethod(
                service,
                "buildRoleSpecificQueries",
                title,
                "股票量化多因子分析师",
                "量化多因子分析师",
                intent
        );

        assertEquals("technical_indicator_research", intent);
        assertNotNull(queries);
        assertFalse(queries.isEmpty());
        assertTrue(queries.get(0).contains("技术指标"));
        assertFalse(String.join(" ", queries).contains("盈利惊喜"));
        assertFalse(String.join(" ", queries).contains("ROE"));
    }

    @Test
    void genericPreventPhraseDoesNotTriggerTechnicalIndicatorIntent() {
        MeetingDebateService service = new MeetingDebateService();

        String intent = ReflectionTestUtils.invokeMethod(
                service,
                "inferIntentFromTitleAndPersona",
                "防止被供应商锁定的采购策略分析",
                "供应链专家"
        );

        assertEquals("background_fact", intent);
    }

    @Test
    void genericTechnicalIndicatorTopicDoesNotBindProjectKeyStock() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        AiMeetingRoom room = new AiMeetingRoom();
        room.setId(63L);
        room.setTitle("找到真正有价值的股票技术面指标，防止被某些技术指标误导");
        room.setDescription("");
        room.setProjectKey("stock-601689-sh-three-month-outlook");
        room.setProjectContext("目标股票：拓普集团（601689.SH）。");

        AiMeetingRoomMapper roomMapper = mock(AiMeetingRoomMapper.class);
        when(roomMapper.selectById(63L)).thenReturn(room);
        ReflectionTestUtils.setField(service, "roomMapper", roomMapper);
        Object plan = newSearchPlan(null);
        ReflectionTestUtils.setField(plan, "intent", "technical_indicator_research");
        ReflectionTestUtils.setField(plan, "eastmoneyFields", List.of("quote", "technicalIndicators"));

        Object ref = ReflectionTestUtils.invokeMethod(
                service,
                "resolveEastmoneyStockRef",
                63L,
                plan
        );

        assertEquals(null, ref);
    }

    @Test
    void explicitDescriptionTargetStillBindsEastmoneyForTechnicalIndicatorResearch() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        AiMeetingRoom room = new AiMeetingRoom();
        room.setId(64L);
        room.setTitle("雷赛智能技术指标有效性分析");
        room.setDescription("{\"stockName\":\"雷赛智能\",\"symbol\":\"002979\",\"market\":\"sz\"}");
        room.setProjectKey("stock-601689-sh-three-month-outlook");

        AiMeetingRoomMapper roomMapper = mock(AiMeetingRoomMapper.class);
        when(roomMapper.selectById(64L)).thenReturn(room);
        ReflectionTestUtils.setField(service, "roomMapper", roomMapper);
        Object plan = newSearchPlan(null);
        ReflectionTestUtils.setField(plan, "intent", "technical_indicator_research");
        ReflectionTestUtils.setField(plan, "eastmoneyFields", List.of("quote", "technicalIndicators"));

        Object ref = ReflectionTestUtils.invokeMethod(
                service,
                "resolveEastmoneyStockRef",
                64L,
                plan
        );

        assertNotNull(ref);
        assertEquals("002979", ReflectionTestUtils.getField(ref, "symbol"));
        assertEquals("sz", ReflectionTestUtils.getField(ref, "market"));
    }

    @Test
    void validationDoesNotCrossVerifyIrrelevantHighQualitySources() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult cninfo = new WebSearchService.SearchResult();
        cninfo.source = "巨潮资讯公告";
        cninfo.url = "https://www.cninfo.com.cn/new/disclosure/detail";
        cninfo.snippet = "宁德时代 2026 年一季度报告，营业收入和净利润摘要。";
        cninfo.query = "找到真正有价值的股票技术面指标 技术指标 有效性 回测";
        WebSearchService.SearchResult eastmoney = new WebSearchService.SearchResult();
        eastmoney.source = "东方财富财报";
        eastmoney.url = "https://finance.eastmoney.com/a/2026.html";
        eastmoney.snippet = "宁德时代 2026 年财务数据与机构预测。";
        eastmoney.query = "找到真正有价值的股票技术面指标 技术指标 有效性 回测";
        Object plan = newSearchPlan(null);
        ReflectionTestUtils.setField(plan, "intent", "technical_indicator_research");
        ReflectionTestUtils.setField(plan, "freshnessDays", 30);
        ReflectionTestUtils.setField(plan, "sourceFocus", List.of("cninfo.com.cn", "eastmoney.com"));

        JSONObject validation = ReflectionTestUtils.invokeMethod(
                service,
                "buildValidationSummary",
                List.of(cninfo, eastmoney),
                plan
        );

        assertNotNull(validation);
        assertEquals(false, validation.getBoolean("crossVerified"));
        assertEquals(false, validation.getBoolean("topicRelevant"));
        assertEquals(0, validation.getIntValue("topicRelevantResultCount"));
    }

    @Test
    void extractFactsFromEvidencePrefersStoredFactsOverLegacySnippet() {
        MeetingDebateService service = new MeetingDebateService();
        JSONArray facts = new JSONArray();
        JSONObject storedFact = new JSONObject(true);
        storedFact.put("label", "最新价");
        storedFact.put("path", "quote.price");
        storedFact.put("value", 24.8);
        storedFact.put("unit", "元");
        storedFact.put("rawValue", "24.8元");
        storedFact.put("source", "东方财富结构化数据 002979.sz");
        storedFact.put("sourceType", "STRUCTURED_DATA");
        storedFact.put("rawHash", "sha256:stored");
        facts.add(storedFact);

        JSONObject result = new JSONObject(true);
        result.put("source", "东方财富结构化数据 002979.sz");
        result.put("snippet", "最新价: 99元");
        result.put("facts", facts);

        JSONArray results = new JSONArray();
        results.add(result);
        JSONObject evidence = new JSONObject(true);
        evidence.put("results", results);

        List<?> extracted = ReflectionTestUtils.invokeMethod(
                service,
                "extractFactsFromEvidence",
                evidence.toJSONString(),
                "tester"
        );

        assertNotNull(extracted);
        assertEquals(1, extracted.size());
        Object fact = extracted.get(0);
        assertEquals("最新价", ReflectionTestUtils.getField(fact, "label"));
        assertEquals("24.8元", ReflectionTestUtils.getField(fact, "rawValue"));
        assertFalse("99元".equals(ReflectionTestUtils.getField(fact, "rawValue")));
    }

    @Test
    void factCatalogKeepsStockRoeAndFinancialRoeSeparate() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{"
                + "\"stockDetail\":{\"roe\":18.5},"
                + "\"financials\":[{\"roePct\":17.9}]"
                + "}";

        JSONObject catalog = ReflectionTestUtils.invokeMethod(service, "buildFactCatalog", List.of(result));

        assertNotNull(catalog);
        JSONArray facts = catalog.getJSONArray("facts");
        JSONObject stockRoe = findFact(facts, "最新ROE");
        JSONObject financialRoe = findFact(facts, "最新财报ROE");
        assertEquals("stockDetail.roe", stockRoe.getString("path"));
        assertEquals("financials[0].roePct", financialRoe.getString("path"));
        assertEquals("18.5%", stockRoe.getString("rawValue"));
        assertEquals("17.9%", financialRoe.getString("rawValue"));
    }

    @Test
    void structuredFactExtractorParsesNumericStringsAndSkipsMissingValues() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{"
                + "\"quote\":{\"price\":\"24.80元\",\"changePercent\":\"--\"},"
                + "\"stockDetail\":{\"roe\":\"18.50%\"},"
                + "\"shareholderCounts\":[{\"holderNum\":\"12,345\",\"holderNumRatioPct\":\"-2.30%\"}]"
                + "}";

        JSONObject catalog = ReflectionTestUtils.invokeMethod(service, "buildFactCatalog", List.of(result));

        assertNotNull(catalog);
        JSONArray facts = catalog.getJSONArray("facts");
        assertEquals("24.8元", findFact(facts, "最新价").getString("rawValue"));
        assertEquals("18.5%", findFact(facts, "最新ROE").getString("rawValue"));
        assertEquals("12345户", findFact(facts, "最新股东户数").getString("rawValue"));
        assertEquals("-2.3%", findFact(facts, "股东户数变化率").getString("rawValue"));
        assertFalse(hasFact(facts, "涨跌幅"));
    }

    @Test
    void claimAuditFlagsDifferentStockCodeWhenTargetPlanExists() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{\"quote\":{\"price\":24.8},\"latestNews\":[{\"summary\":\"来源误指向300750.sz\"}]}";

        ChatResult chatResult = new ChatResult("雷赛智能分析引用了300750.SZ的财务数据，趋势偏强。");

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                newSearchPlan("002979")
        );

        assertNotNull(audit);
        assertEquals("unsupported_claims", audit.getString("status"));
        assertEquals(1, audit.getIntValue("stockCodeViolationCount"));
        JSONObject violation = audit.getJSONArray("stockCodeViolations").getJSONObject(0);
        assertEquals("300750", violation.getString("claim"));
        assertEquals("002979", violation.getString("target"));
    }

    @Test
    void claimAuditAllowsExplicitCorrectionMentioningWrongStockCode() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{\"quote\":{\"price\":24.8},\"latestNews\":[{\"summary\":\"来源误指向300750.sz\"}]}";

        ChatResult chatResult = new ChatResult("来源[1]指向300750.SZ，与002979不符，本轮不采纳该错配数据。");

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                newSearchPlan("002979")
        );

        assertNotNull(audit);
        assertEquals(0, audit.getIntValue("stockCodeViolationCount"));
    }

    @Test
    void claimAuditFlagsUnsupportedGuardedQuantTerms() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{\"quote\":{\"price\":24.8}}";

        ChatResult chatResult = new ChatResult("雷赛智能进入我当下的全A量化多头备选池，因子综合得分排名前10%。");

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                null
        );

        assertNotNull(audit);
        assertEquals("unsupported_claims", audit.getString("status"));
        assertTrue(audit.getIntValue("guardedTermViolationCount") >= 3);
        assertTrue(audit.getJSONArray("guardedTermViolations").stream()
                .map(JSONObject.class::cast)
                .anyMatch(item -> "全a量化".equals(item.getString("term"))));
    }

    @Test
    void claimAuditAllowsGuardedTermsWhenEvidenceContainsThem() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "WEB";
        result.snippet = "全A量化 量化多头备选池 因子综合得分 排名前";

        ChatResult chatResult = new ChatResult("雷赛智能进入全A量化量化多头备选池，因子综合得分排名前列。");

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                null
        );

        assertNotNull(audit);
        assertEquals("ok", audit.getString("status"));
        assertEquals(0, audit.getIntValue("guardedTermViolationCount"));
    }

    @Test
    void factValidationMarksSameStructuredFactFromTwoSourcesAsVerified() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult eastmoney = structuredResult("东方财富结构化数据 002979.sz", "{\"quote\":{\"price\":24.8}}");
        WebSearchService.SearchResult provider = structuredResult("Tavily 标准化行情 002979.sz", "{\"quote\":{\"price\":24.80}}");

        JSONObject validation = ReflectionTestUtils.invokeMethod(
                service,
                "buildFactValidation",
                List.of(eastmoney, provider)
        );

        assertNotNull(validation);
        assertEquals(1, validation.getIntValue("verifiedFactCount"));
        assertEquals(0, validation.getIntValue("conflictingFactCount"));
        JSONObject check = findCheck(validation.getJSONArray("checks"), "最新价");
        assertEquals("verified", check.getString("status"));
        assertEquals(2, check.getIntValue("maxSupport"));
    }

    @Test
    void factValidationMarksSameStructuredFactDifferentValuesAsConflict() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult eastmoney = structuredResult("东方财富结构化数据 002979.sz", "{\"quote\":{\"price\":24.8}}");
        WebSearchService.SearchResult provider = structuredResult("Tavily 标准化行情 002979.sz", "{\"quote\":{\"price\":26.1}}");

        JSONObject validation = ReflectionTestUtils.invokeMethod(
                service,
                "buildFactValidation",
                List.of(eastmoney, provider)
        );

        assertNotNull(validation);
        assertEquals(0, validation.getIntValue("verifiedFactCount"));
        assertEquals(1, validation.getIntValue("conflictingFactCount"));
        JSONObject check = findCheck(validation.getJSONArray("checks"), "最新价");
        assertEquals("conflict", check.getString("status"));
        assertEquals(2, check.getIntValue("valueCount"));
    }

    @Test
    void claimAuditAcceptsNegativePercentageEquivalentFromStructuredFact() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{\"shareholderCounts\":[{\"holderNumRatioPct\":-2.3}]}";

        ChatResult chatResult = new ChatResult("雷赛智能最新股东户数变化率 -2.30%，筹码略有集中。");

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                chatResult,
                List.of(result),
                null
        );

        assertNotNull(audit);
        assertEquals("ok", audit.getString("status"));
        assertEquals(1, audit.getIntValue("numericClaimCount"));
        assertEquals(0, audit.getJSONArray("unsupportedNumericClaims").size());
    }

    @Test
    void malformedStructuredJsonDoesNotCreateFactCatalog() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = "东方财富结构化数据 002979.sz";
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = "{\"quote\":{\"price\":24.8}";

        JSONObject catalog = ReflectionTestUtils.invokeMethod(service, "buildFactCatalog", List.of(result));

        assertEquals(null, catalog);
    }

    @Test
    void explicitStockCodeBeatsWatchlistNameMentionedInNegativeConstraint() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        AiMeetingRoom room = new AiMeetingRoom();
        room.setId(62L);
        room.setTitle("拓普集团未来三个月价格走势分析");
        room.setDescription("{\"stockName\":\"拓普集团\",\"symbol\":\"601689\",\"market\":\"sh\"}");
        room.setProjectKey("stock-601689-sh-three-month-outlook");
        room.setProjectContext("目标股票：拓普集团（601689.SH）。不得引用双环传动、宁德时代、雷赛智能或其他股票旧数据。");

        AiMeetingRoomMapper roomMapper = mock(AiMeetingRoomMapper.class);
        when(roomMapper.selectById(62L)).thenReturn(room);
        StockWatchItemMapper stockWatchItemMapper = mock(StockWatchItemMapper.class);
        when(stockWatchItemMapper.selectEnabled()).thenReturn(Arrays.asList(
                watchItem("002472", "sz", "双环传动"),
                watchItem("300750", "sz", "宁德时代")
        ));
        ReflectionTestUtils.setField(service, "roomMapper", roomMapper);
        ReflectionTestUtils.setField(service, "stockWatchItemMapper", stockWatchItemMapper);

        Object ref = ReflectionTestUtils.invokeMethod(
                service,
                "resolveEastmoneyStockRef",
                62L,
                newSearchPlan(null)
        );

        assertNotNull(ref);
        assertEquals("601689", ReflectionTestUtils.getField(ref, "symbol"));
        assertEquals("sh", ReflectionTestUtils.getField(ref, "market"));
    }

    @Test
    void validationRejectsStructuredResultWhenTargetStockMismatches() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult eastmoney = structuredResult(
                "东方财富结构化数据 002472.sz",
                "{\"symbol\":\"002472\",\"market\":\"sz\",\"quote\":{\"price\":47.01}}"
        );
        eastmoney.url = "https://quote.eastmoney.com/sz002472.html";
        WebSearchService.SearchResult provider = structuredResult(
                "Tavily 标准化行情 002472.sz",
                "{\"symbol\":\"002472\",\"market\":\"sz\",\"quote\":{\"price\":47.01}}"
        );
        provider.url = "https://example.com/stock/002472";

        JSONObject validation = ReflectionTestUtils.invokeMethod(
                service,
                "buildValidationSummary",
                List.of(eastmoney, provider),
                newSearchPlan("601689")
        );

        assertNotNull(validation);
        assertEquals(false, validation.getBoolean("crossVerified"));
        assertEquals(true, validation.getBoolean("targetMismatch"));
    }

    @Test
    void claimAuditRejectsStructuredEvidenceWhenTargetStockMismatches() throws Exception {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult result = structuredResult(
                "东方财富结构化数据 002472.sz",
                "{\"symbol\":\"002472\",\"market\":\"sz\",\"quote\":{\"price\":47.01}}"
        );
        result.url = "https://quote.eastmoney.com/sz002472.html";

        JSONObject audit = ReflectionTestUtils.invokeMethod(
                service,
                "buildClaimAudit",
                new ChatResult("拓普集团最新价 47.01元，趋势偏强。"),
                List.of(result),
                newSearchPlan("601689")
        );

        assertNotNull(audit);
        assertEquals("unsupported_claims", audit.getString("status"));
        assertEquals(1, audit.getIntValue("targetMismatchViolationCount"));
    }

    @Test
    void executeDebateRoundStopsBeforeMemberWhenMeetingPaused() {
        MeetingDebateService service = new MeetingDebateService();
        AiMeetingMember member = new AiMeetingMember();
        member.setId(141L);
        member.setMeetingId(62L);
        member.setMemberName("股票量化多因子分析师");
        member.setModelType("b6465a2d");
        member.setPersona("量化多因子");

        AiMeetingRoom room = new AiMeetingRoom();
        room.setId(62L);
        room.setStatus(MeetingStatusEnum.PAUSED.getCode());

        AiMeetingRoomMapper roomMapper = mock(AiMeetingRoomMapper.class);
        AiMeetingMemberMapper memberMapper = mock(AiMeetingMemberMapper.class);
        AiMeetingOpinionMapper opinionMapper = mock(AiMeetingOpinionMapper.class);
        MeetingWorkflowService workflowService = mock(MeetingWorkflowService.class);
        when(memberMapper.selectByMeetingId(62L)).thenReturn(List.of(member));
        when(workflowService.resolveMembersForCurrentStage(eq(62L), anyList())).thenReturn(List.of(member));
        when(opinionMapper.selectByMeetingId(62L)).thenReturn(Collections.emptyList());
        when(roomMapper.selectById(62L)).thenReturn(room);
        ReflectionTestUtils.setField(service, "roomMapper", roomMapper);
        ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
        ReflectionTestUtils.setField(service, "opinionMapper", opinionMapper);
        ReflectionTestUtils.setField(service, "workflowService", workflowService);

        List<?> opinions = service.executeDebateRound(62L, 1);

        assertNotNull(opinions);
        assertEquals(0, opinions.size());
    }

    private JSONObject findFact(JSONArray facts, String label) {
        return facts.stream()
                .map(JSONObject.class::cast)
                .filter(item -> label.equals(item.getString("label")))
                .findFirst()
                .orElseThrow();
    }

    private boolean hasFact(JSONArray facts, String label) {
        return facts.stream()
                .map(JSONObject.class::cast)
                .anyMatch(item -> label.equals(item.getString("label")));
    }

    private JSONObject findCheck(JSONArray checks, String label) {
        return checks.stream()
                .map(JSONObject.class::cast)
                .filter(item -> label.equals(item.getString("label")))
                .findFirst()
                .orElseThrow();
    }

    private WebSearchService.SearchResult structuredResult(String source, String snippet) {
        WebSearchService.SearchResult result = new WebSearchService.SearchResult();
        result.source = source;
        result.sourceType = "STRUCTURED_DATA";
        result.snippet = snippet;
        return result;
    }

    private StockWatchItem watchItem(String symbol, String market, String stockName) {
        StockWatchItem item = new StockWatchItem();
        item.setSymbol(symbol);
        item.setMarket(market);
        item.setStockName(stockName);
        item.setEnabled(true);
        return item;
    }

    private Object newSearchPlan(String eastmoneySymbol) throws Exception {
        Class<?> clazz = Class.forName("com.aimeeting.room.service.MeetingDebateService$SearchPlan");
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object plan = constructor.newInstance();
        ReflectionTestUtils.setField(plan, "eastmoneySymbol", eastmoneySymbol);
        return plan;
    }
}
