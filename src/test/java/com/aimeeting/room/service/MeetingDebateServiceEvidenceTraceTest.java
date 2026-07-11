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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

class MeetingDebateServiceEvidenceTraceTest {

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
