package com.aimeeting.room.service;

import com.aimeeting.room.config.ModelRegistryStore;
import com.aimeeting.room.dao.AiMeetingMemberMapper;
import com.aimeeting.room.dao.AiMeetingOpinionMapper;
import com.aimeeting.room.dao.AiMeetingRoomMapper;
import com.aimeeting.room.dao.StockWatchItemMapper;
import com.aimeeting.room.dto.ChatResult;
import com.aimeeting.room.entity.AiMeetingMember;
import com.aimeeting.room.entity.AiMeetingOpinion;
import com.aimeeting.room.entity.AiMeetingRoom;
import com.aimeeting.room.entity.MeetingReviewedTurnSkip;
import com.aimeeting.room.enums.MeetingStatusEnum;
import com.aimeeting.room.enums.OpinionTypeEnum;
import com.aimeeting.room.service.ai.WebSearchService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MeetingDebateReviewedMemberTest {

    @Test
    void reviewedClaimsSharingAUrlRetainTheirSeparateReviewOutcomes() {
        MeetingDebateService service = new MeetingDebateService();
        WebSearchService.SearchResult verified = new WebSearchService.SearchResult();
        verified.source = "n8n 双会话搜索审核：usage";
        verified.url = "https://example.org/report";
        verified.withTrace("n8n-deepseek-reviewed", "CROSS_CHECKED_SEARCH", "question", "pass-claim");
        WebSearchService.SearchResult provisional = new WebSearchService.SearchResult();
        provisional.source = "n8n 待核验候选：growth";
        provisional.url = verified.url;
        provisional.withTrace("n8n-deepseek-provisional", "LOW_CONFIDENCE_SEARCH", "question", "unknown-claim");

        List<WebSearchService.SearchResult> retained = ReflectionTestUtils.invokeMethod(
                service, "dedupeSearchResults", List.of(verified, provisional));
        assertEquals(2, retained.size());
    }

    @Test
    void reviewedPlanningPromptIncludesTheMembersRoleAndPreviousSpeech() {
        MeetingOrchestratorService orchestrator = new MeetingOrchestratorService();
        AiMeetingRoomMapper rooms = mock(AiMeetingRoomMapper.class);
        AiMeetingMemberMapper members = mock(AiMeetingMemberMapper.class);
        AiMeetingOpinionMapper opinions = mock(AiMeetingOpinionMapper.class);
        AiMeetingRoom room = new AiMeetingRoom();
        room.setTitle("宁德时代未来三个月走势");
        AiMeetingMember value = new AiMeetingMember();
        value.setId(1L);
        value.setMemberName("价值分析师");
        AiMeetingMember technical = new AiMeetingMember();
        technical.setId(2L);
        technical.setMemberName("技术趋势分析师");
        technical.setPersona("依据量价趋势分析");
        AiMeetingOpinion earlier = new AiMeetingOpinion();
        earlier.setMemberId(1L);
        earlier.setOpinionType(OpinionTypeEnum.STATEMENT.getCode());
        earlier.setContent("我认为盈利仍需核验。");
        when(rooms.selectById(1L)).thenReturn(room);
        when(members.selectById(2L)).thenReturn(technical);
        when(members.selectByMeetingId(1L)).thenReturn(List.of(value, technical));
        when(opinions.selectByMeetingId(1L)).thenReturn(List.of(earlier));
        ReflectionTestUtils.setField(orchestrator, "roomMapper", rooms);
        ReflectionTestUtils.setField(orchestrator, "memberMapper", members);
        ReflectionTestUtils.setField(orchestrator, "opinionMapper", opinions);

        String prompt = orchestrator.buildReviewedEvidenceNeedsPrompt(1L, 2L, 1);

        assertTrue(prompt.contains("依据量价趋势分析"));
        assertTrue(prompt.contains("价值分析师：我认为盈利仍需核验"));
        assertTrue(prompt.contains("\"webSearch\":false"));
        assertTrue(prompt.contains("0 到 3 个来源域名"));
        assertTrue(prompt.contains("requiredFieldPaths"));
        assertTrue(prompt.contains("不能替代经营活动现金流量净额"));
        assertTrue(prompt.contains("macdDif、macdDea、macdHistogram"));
        assertTrue(prompt.contains("没有单一的 macd 字段"));
    }

    @Test
    void eachMemberPlansAfterThePreviousOpinionAndSearchesOwnGap() {
        Fixture fixture = new Fixture("人工智能算力市场", 3);
        fixture.plan(1, webNeed("value", "算力服务商的最新财报是什么？"));
        fixture.plan(2, webNeed("trend", "算力板块最近的成交趋势是什么？"));
        fixture.plan(3, webNeed("rotation", "算力板块相对其他行业的资金轮动如何？"));
        fixture.reviewSuccess();

        List<AiMeetingOpinion> opinions = fixture.service.executeDebateRound(1L, 1);

        assertEquals(3, opinions.size());
        assertEquals(3, fixture.inserted.size());
        verify(fixture.reviewed, times(3)).search(anyString());
        for (int i = 0; i < 3; i++) {
            JSONObject evidence = JSON.parseObject(fixture.inserted.get(i).getEvidence());
            assertEquals("CROSS_CHECKED", evidence.getString("reviewedSearchStatus"));
            assertTrue(evidence.getJSONObject("reviewedSearchDecision").getJSONArray("needs")
                    .getJSONObject(0).getString("question").contains(
                            i == 0 ? "财报" : i == 1 ? "成交趋势" : "资金轮动"));
        }
        InOrder order = inOrder(fixture.orchestrator, fixture.opinionMapper);
        order.verify(fixture.orchestrator).buildReviewedEvidenceNeedsPrompt(1L, 1L, 1);
        order.verify(fixture.opinionMapper).insert(any());
        order.verify(fixture.orchestrator).buildReviewedEvidenceNeedsPrompt(1L, 2L, 1);
        order.verify(fixture.opinionMapper).insert(any());
        order.verify(fixture.orchestrator).buildReviewedEvidenceNeedsPrompt(1L, 3L, 1);
    }

    @Test
    void identicalReviewedQuestionIsReusedWithinTheRound() {
        Fixture fixture = new Fixture("人工智能算力市场", 2);
        fixture.plan(1, webNeed("capacity", "算力中心的最新投运规模是多少？"));
        fixture.plan(2, webNeed("capacity", "算力中心的最新投运规模是多少？"));
        fixture.reviewSuccess();

        assertEquals(2, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, times(1)).search(anyString());
    }

    @Test
    void memberMayDeclineNewSearchWithoutCallingN8n() {
        Fixture fixture = new Fixture("人工智能算力市场", 1);
        fixture.plan(1, "{\"need\":false,\"needs\":[]}");

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
        assertEquals("NO_SEARCH_NEEDED", JSON.parseObject(fixture.inserted.get(0).getEvidence())
                .getString("reviewedSearchStatus"));
    }

    @Test
    void noSearchDecisionCannotPublishNewNumericFacts() {
        Fixture fixture = new Fixture("人工智能算力市场", 1);
        fixture.plan(1, "{\"need\":false,\"needs\":[]}");
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n最新营收达到123亿元。"));

        assertEquals(0, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.opinionMapper, never()).insert(any());
        verify(fixture.roomMapper).updateStatus(1L, MeetingStatusEnum.PAUSED.getCode());
    }

    @Test
    void liveBridgePlannerAliasesAreNormalizedWithoutTrustingFreeTextSourceFocus() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"items\":[{\"id\":\"trend\","
                + "\"question\":\"宁德时代MA120位置如何？\",\"eastmoneySymbol\":\"300750\","
                + "\"eastmoneyMarket\":\"SZ\",\"eastmoneyFields\":[\"technicalIndicators\"],"
                + "\"webSearch\":true,\"webQuestion\":\"宁德时代MA120是多少？\","
                + "\"sourceFocus\":\"趋势与技术指标\"}]}");
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers
                .argThat(question -> question.contains("本次唯一待核验缺口：宁德时代MA120是多少？")
                        && !question.contains("site:趋势与技术指标")));
        JSONObject decision = JSON.parseObject(fixture.inserted.get(0).getEvidence())
                .getJSONObject("reviewedSearchDecision");
        assertEquals(1, decision.getJSONArray("needs").size());
        assertTrue(decision.getJSONArray("needs").getJSONObject(0).getJSONArray("sourceFocus").isEmpty());
    }

    @Test
    void excessValidSourceDomainsAreBoundedWithoutSkippingTheMember() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"needs\":[{\"id\":\"industry\","
                + "\"question\":\"储能行业最近的需求数据是什么？\",\"eastmoneyFields\":[],"
                + "\"webSearch\":true,\"sourceFocus\":[\"cninfo.com.cn\",\"catl.com\","
                + "\"reuters.com\",\"bloomberg.com\",\"miit.gov.cn\"]}]}");
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("site:cninfo.com.cn site:catl.com site:reuters.com")
                        && !question.contains("site:bloomberg.com")
                        && !question.contains("site:miit.gov.cn")));
        JSONObject need = JSON.parseObject(fixture.inserted.get(0).getEvidence())
                .getJSONObject("reviewedSearchDecision").getJSONArray("needs").getJSONObject(0);
        assertEquals(List.of("cninfo.com.cn", "catl.com", "reuters.com"), need.getJSONArray("sourceFocus"));
        assertTrue(need.getBooleanValue("sourceFocusTruncated"));
        assertEquals(2, need.getIntValue("sourceFocusDiscardedCount"));
    }

    @Test
    void invalidSourceDomainBeyondTheLimitIsStillRejected() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        String raw = "{\"need\":true,\"needs\":[{\"id\":\"industry\","
                + "\"question\":\"储能行业最近的需求数据是什么？\",\"eastmoneyFields\":[],"
                + "\"webSearch\":true,\"sourceFocus\":[\"cninfo.com.cn\",\"catl.com\","
                + "\"reuters.com\",\"bad domain\"]}]}";

        MeetingReviewedEvidenceService.EvidenceRejected rejected = assertThrows(
                MeetingReviewedEvidenceService.EvidenceRejected.class,
                () -> ReflectionTestUtils.invokeMethod(fixture.service,
                        "parseReviewedSearchDecision", 1L, raw));
        assertEquals("MEMBER_SEARCH_SOURCE_LIMIT", rejected.getMessage());
    }

    @Test
    void extraSearchGapsAreRecordedAsUnsearchedAndTheMemberCanSpeak() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"needs\":[{\"id\":\"industry\","
                + "\"question\":\"储能行业最近的情况如何？\","
                + "\"eastmoneyFields\":[\"gapOne\",\"gapTwo\",\"gapThree\","
                + "\"gapFour\",\"gapFive\"],\"webSearch\":false,\"sourceFocus\":[]}]}");
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, times(4)).search(anyString());
        JSONObject evidence = JSON.parseObject(fixture.inserted.get(0).getEvidence());
        assertEquals("LOW_CONFIDENCE", evidence.getString("reviewedSearchStatus"));
        assertEquals("UNSEARCHED_BUDGET", evidence.getString("reviewedSearchCoverageStatus"));
        assertEquals(1, evidence.getJSONObject("reviewedSearchDecision")
                .getIntValue("unsearchedGapCount"));
        assertTrue(evidence.getJSONObject("reviewedSearchDecision")
                .getJSONArray("unsearchedGaps").getString(0).contains("gapFive"));
    }

    @Test
    void citationCaptureFailureKeepsTheMemberSpeakingWithoutUsingThatGap() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, webNeed("industry", "储能需求如何？"));
        when(fixture.reviewed.search(anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            JSONObject packet = JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                    + "\"requestId\":\"member-test\",\"status\":\"ERROR\","
                    + "\"claims\":[],\"provisionalClaims\":[],\"audit\":{\"searchCalls\":1,"
                    + "\"trail\":[{\"phase\":\"search\",\"status\":\"ERROR\","
                    + "\"reason\":\"CLAIM_CITATION_NOT_CAPTURED\"}]}}");
            packet.put("question", question);
            return MeetingReviewedEvidenceService.acceptPacket(packet, "member-test", question);
        });

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        JSONObject evidence = JSON.parseObject(fixture.inserted.get(0).getEvidence());
        assertEquals("LOW_CONFIDENCE", evidence.getString("reviewedSearchStatus"));
        assertEquals("UNVERIFIED_GAP", evidence.getString("reviewedSearchCoverageStatus"));
        assertEquals(0, evidence.getJSONObject("reviewedSearchDecision")
                .getIntValue("unsearchedGapCount"));
        assertEquals(1, evidence.getJSONObject("reviewedSearchDecision")
                .getIntValue("unverifiedGapCount"));
        assertEquals("0", evidence.getBigDecimal("reviewedSearchConfidenceScore").toPlainString());
    }

    @Test
    void terminalReviewErrorDropsOnlyThatGapAndKeepsEarlierReviewedFacts() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"needs\":[{\"id\":\"cash\","
                + "\"question\":\"最近一期现金流是多少？\",\"eastmoneyFields\":[],"
                + "\"webSearch\":true,\"sourceFocus\":[]},{\"id\":\"risk\","
                + "\"question\":\"风险公告有哪些？\",\"eastmoneyFields\":[],"
                + "\"webSearch\":true,\"sourceFocus\":[]}]}");
        when(fixture.reviewed.search(anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            if (question.contains("风险公告")) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_REVIEW_ERROR");
            }
            return reviewedResult(question);
        });

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        JSONObject evidence = JSON.parseObject(fixture.inserted.get(0).getEvidence());
        assertEquals("LOW_CONFIDENCE", evidence.getString("reviewedSearchStatus"));
        assertEquals("UNVERIFIED_GAP", evidence.getString("reviewedSearchCoverageStatus"));
        assertEquals(1, evidence.getJSONArray("reviewedSearchPackets").size());
        assertEquals(1, evidence.getJSONObject("reviewedSearchDecision")
                .getIntValue("unverifiedGapCount"));
        assertEquals("N8N_REVIEW_ERROR", evidence.getJSONObject("reviewedSearchDecision")
                .getJSONArray("unverifiedGapFailures").getJSONObject(0).getString("reason"));
        verify(fixture.reviewed, times(2)).search(anyString());
    }

    @Test
    void partiallyReviewedCitationGapCanSpeakButRecordsMissingCoverage() {
        for (String limitation : List.of("CLAIM_CITATION_NOT_CAPTURED", "REVIEW_INCOMPLETE",
                "SEARCH_INCOMPLETE")) {
            Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
            fixture.plan(1, webNeed("industry", "储能需求如何？"));
            when(fixture.reviewed.search(anyString())).thenAnswer(invocation -> {
                String question = invocation.getArgument(0);
                JSONObject packet = JSON.parseObject(reviewedResult(question).packet.toJSONString());
                packet.put("status", "PARTIAL");
                packet.put("limitations", List.of(limitation));
                return MeetingReviewedEvidenceService.acceptPacket(packet, "member-test", question);
            });

            assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
            JSONObject evidence = JSON.parseObject(fixture.inserted.get(0).getEvidence());
            assertEquals("LOW_CONFIDENCE", evidence.getString("reviewedSearchStatus"));
            assertEquals("UNVERIFIED_GAP", evidence.getString("reviewedSearchCoverageStatus"));
            assertEquals("0.25", evidence.getBigDecimal("reviewedSearchConfidenceScore").toPlainString());
            assertEquals("INCOMPLETE_REVIEW", evidence.getJSONObject("reviewedSearchDecision")
                    .getString("unverifiedGapReason"));
            ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
            verify(fixture.orchestrator).buildDebateContext(eq(1L), eq(1L), eq(1), context.capture());
            assertTrue(context.getValue().contains("部分资料已审核通过的缺口"));
            assertTrue(context.getValue().contains("已审核的事实仍可引用"));
            assertFalse(context.getValue().contains("本轮没有可引用事实的缺口"));
            ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
            verify(fixture.orchestrator).callModelExStrict(eq("deepseek-chat"), system.capture(), anyString());
            assertTrue(system.getValue().contains("不得自行新增差值、比率、利润率、目标价等数字"));
            assertTrue(system.getValue().contains("口径未经校验时不得混用"));
            assertTrue(system.getValue().contains("写带负号的涨跌幅，不改写为正数跌幅"));
        }
    }

    @Test
    void failedGapPromptSaysNoUsableFactsWhileKeepingAnotherAcceptedGap() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"needs\":[{\"id\":\"cash\","
                + "\"question\":\"最近一期现金流是多少？\",\"eastmoneyFields\":[],"
                + "\"webSearch\":true,\"sourceFocus\":[]},{\"id\":\"risk\","
                + "\"question\":\"风险公告有哪些？\",\"eastmoneyFields\":[],"
                + "\"webSearch\":true,\"sourceFocus\":[]}]}");
        when(fixture.reviewed.search(anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            if (question.contains("风险公告")) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_REVIEW_ERROR");
            }
            return reviewedResult(question);
        });

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        ArgumentCaptor<String> context = ArgumentCaptor.forClass(String.class);
        verify(fixture.orchestrator).buildDebateContext(eq(1L), eq(1L), eq(1), context.capture());
        assertTrue(context.getValue().contains("本轮没有可引用事实的缺口"));
        assertFalse(context.getValue().contains("部分资料已审核通过的缺口"));
        JSONObject decision = JSON.parseObject(fixture.inserted.get(0).getEvidence())
                .getJSONObject("reviewedSearchDecision");
        assertEquals("NO_USABLE_REVIEWED_FACT", decision.getString("unverifiedGapReason"));
        assertEquals(1, decision.getJSONArray("noUsableReviewedGaps").size());
    }

    @Test
    void searchBudgetReservesOneQuestionForEachPlannedNeed() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"needs\":[{\"id\":\"valuation\","
                + "\"question\":\"估值数据如何？\",\"eastmoneyFields\":[\"gapOne\","
                + "\"gapTwo\",\"gapThree\",\"gapFour\",\"gapFive\"],"
                + "\"webSearch\":false,\"sourceFocus\":[]},{\"id\":\"industry\","
                + "\"question\":\"储能需求如何？\",\"eastmoneyFields\":[\"industryGap\"],"
                + "\"webSearch\":false,\"sourceFocus\":[]}]}");
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, times(4)).search(anyString());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：industryGap")));
    }

    @Test
    void availableEastmoneyFieldDoesNotTriggerN8n() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\","
                + "\"needs\":[{\"id\":\"price\",\"question\":\"宁德时代最新价是多少？\","
                + "\"eastmoneyFields\":[\"quote\"],\"webSearch\":false,\"sourceFocus\":[]}]}");
        Map<String, Object> quote = Map.of("price", 291.11);
        Map<String, Object> status = Map.of("quote", Map.of("ok", true));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("asOf", "2026-10-06T10:00:00+08:00");
        raw.put("symbol", "300750");
        raw.put("market", "sz");
        raw.put("quote", Map.of("price", quote.get("price"), "timestamp",
                java.time.ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toEpochSecond()));
        raw.put("dataQuality", Map.of("sourceStatus", status));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
        assertEquals("STRUCTURED_ONLY", JSON.parseObject(fixture.inserted.get(0).getEvidence())
                .getString("reviewedSearchStatus"));
    }

    @Test
    void eastmoneyMetadataWithoutRequestedFieldStillCreatesSearchGap() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\","
                + "\"needs\":[{\"id\":\"price\",\"question\":\"宁德时代最新价是多少？\","
                + "\"eastmoneyFields\":[\"quote\"],\"webSearch\":false,\"sourceFocus\":[]}]}");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("asOf", "2026-10-06T10:00:00+08:00");
        raw.put("symbol", "300750");
        raw.put("market", "sz");
        raw.put("dataQuality", Map.of("requiredMissing", List.of("quote:unavailable")));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw,
                        List.of("quote:unavailable"), List.of()));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers
                .argThat(question -> question.contains("本次唯一待核验缺口：quote")));
        assertEquals("CROSS_CHECKED", JSON.parseObject(fixture.inserted.get(0).getEvidence())
                .getString("reviewedSearchStatus"));
    }

    @Test
    void granularFieldFromLivePlannerOutputDoesNotHideMissingOrUnsupportedMetric() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\","
                + "\"needs\":[{\"id\":\"indicators\",\"question\":\"宁德时代MA60、MA120与最近20日主力资金和融资余额如何？\","
                + "\"eastmoneyFields\":[\"ma60\",\"ma120\",\"fundFlow\",\"volumeRatio\",\"margin\"],"
                + "\"webSearch\":false,\"sourceFocus\":[]}]}");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "300750");
        raw.put("market", "sz");
        raw.put("quote", Map.of("price", 291.11));
        raw.put("stockDetail", Map.of("volumeRatio", 0.91));
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = 0; i < 35; i++) bars.add(Map.of("date", LocalDate.now().minusDays(34L - i).toString(),
                "volume", 100, "turnoverPct", 1.2));
        raw.put("dailyKline", bars);
        raw.put("technicalIndicators", Map.of("ma20", 290.0));
        raw.put("dataQuality", Map.of("sourceStatus", Map.of(
                "dailyKline", Map.of("ok", true), "stockDetail", Map.of("ok", true))));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, times(4)).search(anyString());
        for (String gap : List.of("ma60", "ma120", "20日主力资金汇总", "融资余额")) {
            verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                    question.toLowerCase().contains("本次唯一待核验缺口：" + gap.toLowerCase())
                            && question.contains("其他字段不算本次审核通过")));
        }
        assertEquals("CROSS_CHECKED", JSON.parseObject(fixture.inserted.get(0).getEvidence())
                .getString("reviewedSearchStatus"));
    }

    @Test
    void populatedMa60CanBeSatisfiedWithoutWebSearch() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\","
                + "\"needs\":[{\"id\":\"ma60\",\"question\":\"宁德时代MA60是多少？\","
                + "\"eastmoneyFields\":[\"ma60\"],\"webSearch\":false,\"sourceFocus\":[]}]}");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "300750");
        raw.put("market", "sz");
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = 0; i < 65; i++) bars.add(Map.of("date", LocalDate.now().minusDays(64L - i).toString(),
                "close", 290.0));
        raw.put("dailyKline", bars);
        raw.put("technicalIndicators", Map.of("ma20", 290.0, "ma60", 280.0));
        raw.put("dataQuality", Map.of("sourceStatus", Map.of("dailyKline", Map.of("ok", true))));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
        assertEquals("STRUCTURED_ONLY", JSON.parseObject(fixture.inserted.get(0).getEvidence())
                .getString("reviewedSearchStatus"));
    }

    @Test
    void requestedSixtyDayKlineIsActuallyDeliveredToTheMember() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\","
                + "\"needs\":[{\"id\":\"trend\",\"question\":\"宁德时代最近60个交易日走势如何？\","
                + "\"eastmoneyFields\":[\"dailyKline\"],\"webSearch\":false,\"sourceFocus\":[]}]}");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "300750");
        raw.put("market", "sz");
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = 0; i < 70; i++) bars.add(Map.of(
                "date", LocalDate.now().minusDays(69L - i).toString(), "open", 290.0,
                "close", 291.0, "high", 292.0, "low", 289.0, "volume", 1000,
                "amount", 291000.0, "turnoverPct", 1.0));
        raw.put("dailyKline", bars);
        raw.put("dataQuality", Map.of("sourceStatus", Map.of("dailyKline", Map.of("ok", true))));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
        ArgumentCaptor<List> captured = ArgumentCaptor.forClass(List.class);
        verify(fixture.webSearch).formatResultsForPrompt(captured.capture());
        WebSearchService.SearchResult result = (WebSearchService.SearchResult) captured.getValue().get(0);
        assertTrue(JSON.parseObject(result.snippet).getJSONArray("dailyKline").size() >= 60);
    }

    @Test
    void requestedHistoricalFinancialReportIsActuallyDeliveredToTheMember() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\","
                + "\"needs\":[{\"id\":\"older-report\",\"question\":\"宁德时代2024年上半年净利润是多少？\","
                + "\"eastmoneyFields\":[\"financials\"],\"webSearch\":false,\"sourceFocus\":[]}]}");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "300750");
        raw.put("market", "sz");
        raw.put("financials", List.of(
                Map.of("reportDate", "2026-09-30", "netProfit", 11),
                Map.of("reportDate", "2026-06-30", "netProfit", 10),
                Map.of("reportDate", "2026-03-31", "netProfit", 9),
                Map.of("reportDate", "2025-12-31", "netProfit", 8),
                Map.of("reportDate", "2025-09-30", "netProfit", 7),
                Map.of("reportDate", "2024-06-30", "netProfit", 6)));
        raw.put("dataQuality", Map.of("sourceStatus", Map.of("financials", Map.of("ok", true))));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
        ArgumentCaptor<List> captured = ArgumentCaptor.forClass(List.class);
        verify(fixture.webSearch).formatResultsForPrompt(captured.capture());
        WebSearchService.SearchResult result = (WebSearchService.SearchResult) captured.getValue().get(0);
        assertTrue(JSON.parseObject(result.snippet).getJSONArray("financials").stream()
                .anyMatch(row -> row instanceof JSONObject
                        && "2024-06-30".equals(((JSONObject) row).getString("reportDate"))));
    }

    @Test
    void technicalIndicatorsGroupDoesNotCoverMa60MissingFromItsPayload() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\","
                + "\"needs\":[{\"id\":\"ma60\",\"question\":\"宁德时代MA60是多少？\","
                + "\"eastmoneyFields\":[\"technicalIndicators\"],\"webSearch\":false,\"sourceFocus\":[]}]}");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "300750");
        raw.put("market", "sz");
        List<Map<String, Object>> bars = new ArrayList<>();
        for (int i = 0; i < 35; i++) bars.add(Map.of("date", LocalDate.now().minusDays(34L - i).toString()));
        raw.put("dailyKline", bars);
        raw.put("technicalIndicators", Map.of("ma20", 290.0));
        raw.put("dataQuality", Map.of("sourceStatus", Map.of("dailyKline", Map.of("ok", true))));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers
                .argThat(question -> question.contains("本次唯一待核验缺口：ma60")));
    }

    @Test
    void perNeedStockOverridesMeetingStockForStructuredData() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\","
                + "\"needs\":[{\"id\":\"competitor\",\"question\":\"比亚迪最新财报净利润是多少？\","
                + "\"eastmoneySymbol\":\"002594\",\"eastmoneyMarket\":\"sz\","
                + "\"eastmoneyFields\":[\"financials\"],\"webSearch\":false,\"sourceFocus\":[]}]}");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "002594");
        raw.put("market", "sz");
        raw.put("financials", List.of(Map.of("reportDate", "2026-06-30", "netProfit", 100)));
        raw.put("dataQuality", Map.of("sourceStatus", Map.of("financials", Map.of("ok", true))));
        when(fixture.eastmoney.buildBundle("002594", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.eastmoney).buildBundle("002594", "sz", "DAY");
        verify(fixture.eastmoney, never()).buildBundle("300750", "sz", "DAY");
        verify(fixture.reviewed, never()).search(anyString());
    }

    @Test
    void cashFlowRatioDoesNotCoverRequestedOperatingCashFlowAmount() {
        Fixture fixture = structuredNeedFixture("最新财报经营活动现金流量净额是多少？", "financials", null);
        fixture.bundle("financials", List.of(Map.of("reportDate", "2026-06-30",
                "cashToRevenuePct", 31.2, "revenue", 1000)));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("netOperatingCashFlow") && question.contains("现金流量净额")));
    }

    @Test
    void presentRequiredMetricCanBeSatisfiedWithoutWebSearch() {
        Fixture fixture = structuredNeedFixture("最新财报经营活动现金流量净额是多少？", "financials",
                "financials[].netOperatingCashFlow");
        fixture.bundle("financials", List.of(Map.of("reportDate", "2026-06-30",
                "netOperatingCashFlow", 312)));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
        assertTrue(JSON.parseObject(fixture.inserted.get(0).getEvidence())
                .getJSONObject("reviewedSearchDecision").getJSONArray("needs").getJSONObject(0)
                .getJSONArray("requiredFieldPaths").contains("financials[].netOperatingCashFlow"));
    }

    @Test
    void missingRequiredMetricCannotBeFilledByAnotherFinancialPeriod() {
        Fixture fixture = structuredNeedFixture("2026年上半年经营活动现金流量净额是多少？", "financials",
                "financials[].netOperatingCashFlow");
        fixture.bundle("financials", List.of(Map.of("reportDate", "2026-06-30", "revenue", 1000),
                Map.of("reportDate", "2025-12-31", "netOperatingCashFlow", 312)));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("netOperatingCashFlow")));
    }

    @Test
    void oneYearPercentileDoesNotCoverRequestedFiveYearPercentile() {
        Fixture fixture = structuredNeedFixture("当前PE TTM在最近五年中的估值分位是多少？", "valuation", null);
        fixture.bundle("valuation", Map.of("peTtm", 20, "pePercentile1y", 36));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("pePercentile5y")));
    }

    @Test
    void announcementIndexCannotSatisfySpecificEventFacts() {
        Fixture fixture = structuredNeedFixture("最新产能扩张公告披露了什么投资金额及风险？", "announcements", null);
        fixture.bundle("announcements", List.of(Map.of("title", "关于产能扩张的公告", "noticeDate", "2026-10-01")));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：announcements")));
    }

    @Test
    void explicitAnnouncementTitleListRemainsStructuredOnly() {
        Fixture fixture = structuredNeedFixture("最新公告标题列表有哪些？", "announcements", null);
        fixture.bundle("announcements", List.of(Map.of("title", "关于产能扩张的公告", "noticeDate", "2026-10-01")));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
    }

    @Test
    void matchingIntradayDatesDoNotEstablishCompletedDailyBar() {
        Fixture fixture = structuredNeedFixture("最近一个已收盘交易日的日K是什么？", "dailyKline", null);
        fixture.completedBarBundle("10:30:00");
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：dailyKline")));
    }

    @Test
    void dailyBarsCapturedAfterMarketCloseSatisfyCompletedBarNeed() {
        Fixture fixture = structuredNeedFixture("最近一个已收盘交易日的日K是什么？", "dailyKline", null);
        fixture.completedBarBundle("16:00:00");

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
    }

    @Test
    void completedTechnicalIndicatorCannotUseAnIntradayDailyBar() {
        Fixture fixture = structuredNeedFixture("最近一个已收盘交易日的RSI是多少？", "technicalIndicators", null);
        fixture.completedBarBundle("10:30:00");
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：technicalIndicators")));
    }

    @Test
    void lateCaptureOfAnIntradayQuoteDoesNotMakeItAClosingPrice() {
        Fixture fixture = structuredNeedFixture("最近一个交易日的收盘价是多少？", "quote", null);
        Map<String, Object> raw = new LinkedHashMap<>();
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        raw.put("symbol", "300750");
        raw.put("market", "sz");
        raw.put("asOf", date + "T16:00:00+08:00");
        raw.put("quote", Map.of("price", 291.11, "timestamp", date.atTime(10, 30)
                .atZone(ZoneId.of("Asia/Shanghai")).toEpochSecond()));
        raw.put("dataQuality", Map.of("sourceStatus", Map.of("quote", Map.of("ok", true))));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：quote")));
    }

    @Test
    void exactAnnouncementTitleCanProveItsPublicationDateWithoutWebSearch() {
        Fixture fixture = structuredNeedFixture("《关于产能扩张的风险公告》的发布日期是什么？", "announcements", null);
        fixture.bundle("announcements", List.of(Map.of("title", "关于产能扩张的风险公告", "noticeDate", "2026-10-01")));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
    }

    @Test
    void requiredFieldPathCannotReadASectionNotRequestedByThePlan() {
        Fixture fixture = structuredNeedFixture("最新财报净利润是多少？", "financials", "quote.price");
        assertEquals(0, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.eastmoney, never()).buildBundle(anyString(), anyString(), anyString());
        verify(fixture.reviewed, never()).search(anyString());
    }

    @Test
    void arbitraryRequiredFieldExpressionsAreRejected() {
        Fixture fixture = structuredNeedFixture("最新财报净利润是多少？", "financials", "financials[0].netProfit");
        assertEquals(0, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.eastmoney, never()).buildBundle(anyString(), anyString(), anyString());
        verify(fixture.reviewed, never()).search(anyString());
    }

    @Test
    void newRequiredMetricCannotBeSilentlySatisfiedByItsPopulatedGroup() {
        Fixture fixture = structuredNeedFixture("最新披露的资本开支金额是多少？", "financials", "financials[].capitalExpenditure");
        fixture.bundle("financials", List.of(Map.of("reportDate", "2026-06-30", "netProfit", 312)));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：financials[].capitalExpenditure")));
    }

    @Test
    void currentYearInQuestionCannotBeMistakenForPercentileHorizon() {
        Fixture fixture = structuredNeedFixture("截至2026年10月的近五年PE分位是多少？", "valuation", null);
        fixture.bundle("valuation", Map.of("peTtm", 20, "pePercentile1y", 36, "pePercentile6y", 42));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：valuation.pePercentile5y")));
    }

    @Test
    void projectedOutRequiredValueBecomesOneResidualGap() {
        Fixture fixture = structuredNeedFixture("当前市盈率是多少？", "valuation", "valuation.peTtm");
        fixture.bundle("valuation", Map.of("peTtm", 20, "unrelatedLongDetail", "x".repeat(16000)));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, times(1)).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：valuation")
                        && question.contains("该缺口必须包含字段：valuation.peTtm")));
    }

    @Test
    void oldPlanWithoutRequiredPathsDoesNotCoverAnUnknownMetric() {
        Fixture fixture = structuredNeedFixture("最新披露的资本开支金额是多少？", "financials", null);
        fixture.bundle("financials", List.of(Map.of("reportDate", "2026-06-30", "netProfit", 312)));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：financials")));
    }

    @Test
    void cumulativeHalfYearReportDoesNotCoverSingleSecondQuarterProfit() {
        Fixture fixture = structuredNeedFixture("截至2026年第二季度末，第二季度单季净利润是多少？", "financials", "financials[].netProfit");
        fixture.bundle("financials", List.of(Map.of("reportDate", "2026-06-30", "netProfit", 312)));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：financials")));
    }

    @Test
    void explicitReportDateCannotBeCoveredByAnotherQuarterInSameYear() {
        Fixture fixture = structuredNeedFixture("2025-06-30报告中的净利润是多少？", "financials", "financials[].netProfit");
        fixture.bundle("financials", List.of(Map.of("reportDate", "2025-12-31", "netProfit", 312)));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：financials")));
    }

    @Test
    void explicitReportPeriodWinsOverEarlierAsOfYear() {
        Fixture fixture = structuredNeedFixture("截至2026年10月，2025年年报营收是多少？", "financials", "financials[].revenue");
        fixture.bundle("financials", List.of(Map.of("reportDate", "2025-12-31", "revenue", 312)));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
    }

    @Test
    void quarterEndPointInTimeMetricIsNotRejectedAsSingleQuarterFlow() {
        Fixture fixture = structuredNeedFixture("2026年第三季度末资产负债率是多少？", "financials", "financials[].debtRatioPct");
        fixture.bundle("financials", List.of(Map.of("reportDate", "2026-09-30", "debtRatioPct", 60)));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
    }

    @Test
    void annualReportAliasCannotBeSatisfiedByThirdQuarterData() {
        Fixture fixture = structuredNeedFixture("2025年年度报告净利润是多少？", "financials", "financials[].netProfit");
        fixture.bundle("financials", List.of(Map.of("reportDate", "2025-09-30", "netProfit", 312)));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：financials")));
    }

    @Test
    void macdRequiredPathAliasExpandsToConcreteDeliveredComponents() {
        Fixture fixture = structuredNeedFixture("技术指标快照", "technicalIndicators", "technicalIndicators.macd");
        fixture.completedBarBundle("16:00:00", Map.of("ma20", 290, "macdDif", 1.2,
                "macdDea", 0.9, "macdHistogram", 0.6, "rsi14", 54));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
        List<String> paths = JSON.parseObject(fixture.inserted.get(0).getEvidence())
                .getJSONObject("reviewedSearchDecision").getJSONArray("needs").getJSONObject(0)
                .getJSONArray("requiredFieldPaths").toJavaList(String.class);
        assertEquals(List.of("technicalIndicators.macdDif", "technicalIndicators.macdDea",
                "technicalIndicators.macdHistogram"), paths);
    }

    @Test
    void turnoverRateAliasUsesAvailableQuoteTurnoverWithoutSearching() {
        Fixture fixture = structuredNeedFixture("当前换手率是多少？", "quote", "quote.turnoverRate");
        fixture.completedBarBundle("16:00:00");
        Map<String, Object> raw = fixture.eastmoney.buildBundle("300750", "sz", "DAY").getPayload();
        List<Map<String, Object>> bars = (List<Map<String, Object>>) raw.get("dailyKline");
        Map<String, Object> latest = new LinkedHashMap<>(bars.get(bars.size() - 1));
        latest.put("turnoverPct", 0.86);
        bars.set(bars.size() - 1, latest);
        raw.put("quote", Map.of("price", 286.75, "turnover", 0.86,
                "timestamp", java.time.Instant.now().getEpochSecond()));
        raw.put("dataQuality", Map.of("sourceStatus", Map.of("quote", Map.of("ok", true),
                "dailyKline", Map.of("ok", true))));

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
        List<String> paths = JSON.parseObject(fixture.inserted.get(0).getEvidence())
                .getJSONObject("reviewedSearchDecision").getJSONArray("needs").getJSONObject(0)
                .getJSONArray("requiredFieldPaths").toJavaList(String.class);
        assertEquals(List.of("quote.turnover"), paths);
    }

    @Test
    void turnoverRateAliasDoesNotCoverMissingQuoteTurnover() {
        Fixture fixture = structuredNeedFixture("当前换手率是多少？", "quote", "quote.turnoverRate");
        fixture.completedBarBundle("16:00:00");
        Map<String, Object> raw = fixture.eastmoney.buildBundle("300750", "sz", "DAY").getPayload();
        List<Map<String, Object>> bars = (List<Map<String, Object>>) raw.get("dailyKline");
        Map<String, Object> latest = new LinkedHashMap<>(bars.get(bars.size() - 1));
        latest.put("turnoverPct", 0.86);
        bars.set(bars.size() - 1, latest);
        raw.put("quote", Map.of("price", 286.75,
                "timestamp", java.time.Instant.now().getEpochSecond()));
        raw.put("dataQuality", Map.of("sourceStatus", Map.of("quote", Map.of("ok", true),
                "dailyKline", Map.of("ok", true))));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：quote.turnover。")));
    }

    @Test
    void macdAliasStillRequiresEveryComponent() {
        Fixture fixture = structuredNeedFixture("技术指标快照", "technicalIndicators", "technicalIndicators.MACD");
        fixture.completedBarBundle("16:00:00", Map.of("ma20", 290, "macdDif", 1.2,
                "macdHistogram", 0.6, "rsi14", 54));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, times(1)).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：technicalIndicators.macdDea")));
    }

    @Test
    void unknownTechnicalRequiredPathRemainsARealGap() {
        Fixture fixture = structuredNeedFixture("技术指标快照", "technicalIndicators", "technicalIndicators.unknownOscillator");
        fixture.completedBarBundle("16:00:00", Map.of("ma20", 290, "macdDif", 1.2,
                "macdDea", 0.9, "macdHistogram", 0.6, "rsi14", 54));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, times(1)).search(org.mockito.ArgumentMatchers.argThat(question ->
                question.contains("本次唯一待核验缺口：technicalIndicators.unknownOscillator")));
    }

    @Test
    void expandedMacdPlanCanBeParsedAgainWithinTheOriginalRequirementBudget() {
        MeetingDebateService service = new MeetingDebateService();
        JSONObject planNeed = JSON.parseObject("{\"requiredFieldPaths\":[\"technicalIndicators.macd\","
                + "\"technicalIndicators.ma5\",\"technicalIndicators.ma10\",\"technicalIndicators.ma20\","
                + "\"technicalIndicators.ma60\",\"technicalIndicators.rsi14\",\"technicalIndicators.atr14\","
                + "\"technicalIndicators.bollMiddle\"]}");
        List<String> normalized = ReflectionTestUtils.invokeMethod(service, "reviewedRequiredFieldPaths",
                planNeed, List.of("technicalIndicators"), "技术指标快照");
        assertEquals(10, normalized.size());
        JSONObject restoredNeed = JSON.parseObject(JSON.toJSONString(Map.of("requiredFieldPaths", normalized)));
        assertEquals(normalized, ReflectionTestUtils.invokeMethod(service, "reviewedRequiredFieldPaths",
                restoredNeed, List.of("technicalIndicators"), "技术指标快照"));
    }

    private static Fixture structuredNeedFixture(String question, String field, String requiredPath) {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        JSONObject need = new JSONObject();
        need.put("id", "needed");
        need.put("question", question);
        need.put("eastmoneyFields", List.of(field));
        need.put("webSearch", false);
        need.put("sourceFocus", List.of());
        if (requiredPath != null) need.put("requiredFieldPaths", List.of(requiredPath));
        JSONObject plan = new JSONObject();
        plan.put("need", true);
        plan.put("eastmoneySymbol", "300750");
        plan.put("eastmoneyMarket", "sz");
        plan.put("needs", List.of(need));
        fixture.plan(1, plan.toJSONString());
        return fixture;
    }

    @Test
    void staleOrWrongStockQuoteCannotSkipReviewedGap() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\","
                + "\"needs\":[{\"id\":\"price\",\"question\":\"宁德时代最新股价是多少？\","
                + "\"eastmoneyFields\":[\"quote\"],\"webSearch\":false,\"sourceFocus\":[]}]}");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "300750");
        raw.put("market", "sz");
        raw.put("quote", Map.of("price", 291.11, "timestamp",
                java.time.ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).minusDays(30).toEpochSecond()));
        raw.put("dataQuality", Map.of("sourceStatus", Map.of("quote", Map.of("ok", true))));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers
                .argThat(question -> question.contains("本次唯一待核验缺口：quote")));
    }

    @Test
    void oldFinancialReportDoesNotCoverRequestedHalfYear() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\","
                + "\"needs\":[{\"id\":\"halfyear\",\"question\":\"宁德时代2026年上半年净利润是多少？\","
                + "\"eastmoneyFields\":[\"financials\"],\"webSearch\":false,\"sourceFocus\":[]}]}");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "300750");
        raw.put("market", "sz");
        raw.put("financials", List.of(Map.of("reportDate", "2025-12-31", "netProfit", 100)));
        raw.put("dataQuality", Map.of("sourceStatus", Map.of("financials", Map.of("ok", true))));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers
                .argThat(question -> question.contains("本次唯一待核验缺口：financials")));
    }

    @Test
    void mismatchedStructuredPayloadStockIsRejected() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 1);
        fixture.plan(1, "{\"need\":true,\"eastmoneySymbol\":\"300750\",\"eastmoneyMarket\":\"sz\","
                + "\"needs\":[{\"id\":\"price\",\"question\":\"宁德时代最新股价是多少？\","
                + "\"eastmoneyFields\":[\"quote\"],\"webSearch\":false,\"sourceFocus\":[]}]}");
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("symbol", "002594");
        raw.put("market", "sz");
        raw.put("quote", Map.of("price", 291.11, "timestamp",
                java.time.ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toEpochSecond()));
        raw.put("dataQuality", Map.of("sourceStatus", Map.of("quote", Map.of("ok", true))));
        when(fixture.eastmoney.buildBundle("300750", "sz", "DAY"))
                .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));
        fixture.reviewSuccess();

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed).search(org.mockito.ArgumentMatchers
                .argThat(question -> question.contains("本次唯一待核验缺口：quote")));
    }

    @Test
    void invalidMemberPlanPausesWithoutHeuristicOrSearchFallback() {
        Fixture fixture = new Fixture("人工智能算力市场", 1);
        fixture.plan(1, "我认为需要搜索，但暂时没有计划");

        assertEquals(0, fixture.service.executeDebateRound(1L, 1).size());
        verify(fixture.reviewed, never()).search(anyString());
        verify(fixture.opinionMapper, never()).insert(any());
        verify(fixture.roomMapper).updateStatus(1L, MeetingStatusEnum.PAUSED.getCode());
    }

    @Test
    void failureForSecondMemberPausesAfterSavingOnlyFirstOpinion() {
        Fixture fixture = new Fixture("人工智能算力市场", 2);
        fixture.plan(1, webNeed("value", "算力服务商的最新财报是什么？"));
        fixture.plan(2, webNeed("trend", "算力板块最近的成交趋势是什么？"));
        when(fixture.reviewed.search(anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            if (question.contains("成交趋势")) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_REVIEW_UNVERIFIED");
            }
            return reviewedResult(question);
        });

        assertEquals(1, fixture.service.executeDebateRound(1L, 1).size());
        assertEquals(1, fixture.inserted.size());
        verify(fixture.roomMapper).updateStatus(1L, MeetingStatusEnum.PAUSED.getCode());
        verify(fixture.workflow).markReviewedEvidenceBlocked(eq(1L), anyString());
    }

    @Test
    void memberLocalReviewFailurePersistsAbstentionAndLaterMembersStillSpeak() {
        Fixture fixture = new Fixture("人工智能算力市场", 3);
        MeetingReviewedTurnSkipService skips = fixture.enableSkips();
        fixture.plan(1, webNeed("value", "算力服务商的最新财报是什么？"));
        fixture.plan(2, webNeed("trend", "算力板块最近的成交趋势是什么？"));
        fixture.plan(3, webNeed("rotation", "算力板块相对其他行业的资金轮动如何？"));
        when(fixture.reviewed.search(anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            if (question.contains("成交趋势")) {
                throw new MeetingReviewedEvidenceService.EvidenceRejected("N8N_REVIEW_UNVERIFIED");
            }
            return reviewedResult(question);
        });

        List<AiMeetingOpinion> result = fixture.service.executeDebateRound(1L, 1);

        assertEquals(2, result.size());
        assertEquals(List.of(1L, 3L), result.stream().map(AiMeetingOpinion::getMemberId)
                .collect(Collectors.toList()));
        assertEquals(2, fixture.inserted.size());
        verify(skips).recordIfCurrent(org.mockito.ArgumentMatchers.argThat(skip ->
                        skip.getMemberId() == 2L
                                && "N8N_REVIEW_UNVERIFIED".equals(skip.getReasonCode())),
                eq(MeetingStatusEnum.IN_PROGRESS.getCode()), nullable(Long.class));
        verify(fixture.roomMapper, never()).updateStatus(1L, MeetingStatusEnum.PAUSED.getCode());
        fixture.service.executeDebateRound(1L, 1);
        verify(fixture.orchestrator, times(1)).buildReviewedEvidenceNeedsPrompt(1L, 2L, 1);
    }

    @Test
    void silentConflictWithPriorVerifiedNumberSkipsOnlyThatSpeaker() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 3);
        MeetingReviewedTurnSkipService skips = fixture.enableSkips();
        fixture.plan(1, webNeedForStock("revenue1", "2025年宁德时代营收初始公告是多少？", "300750"));
        fixture.plan(2, webNeedForStock("revenue2", "2025年宁德时代营收修订公告是多少？", "300750"));
        fixture.plan(3, "{\"need\":false,\"needs\":[]}");
        when(fixture.reviewed.search(anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            return reviewedNumericResult(question, question.contains("初始")
                    ? "2025年营收：100亿元" : "2025年营收：120亿元");
        });
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n2025年营收：100亿元。"),
                        new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n2025年营收：120亿元。"),
                        new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n等待更充分证据。"));

        List<AiMeetingOpinion> opinions = fixture.service.executeDebateRound(1L, 1);

        assertEquals(List.of(1L, 3L), opinions.stream().map(AiMeetingOpinion::getMemberId)
                .collect(Collectors.toList()));
        assertEquals(2, fixture.inserted.size());
        verify(skips).recordIfCurrent(org.mockito.ArgumentMatchers.argThat(skip ->
                        skip.getMemberId() == 2L
                                && "MODEL_CROSS_MEMBER_NUMERIC_CONFLICT".equals(skip.getReasonCode())),
                eq(MeetingStatusEnum.IN_PROGRESS.getCode()), nullable(Long.class));
        verify(fixture.roomMapper, never()).updateStatus(1L, MeetingStatusEnum.PAUSED.getCode());
    }

    @Test
    void differentReportingYearsDoNotConflict() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 2);
        fixture.plan(1, webNeedForStock("revenue1", "2025年宁德时代营收是多少？", "300750"));
        fixture.plan(2, webNeedForStock("revenue2", "2026年宁德时代营收是多少？", "300750"));
        when(fixture.reviewed.search(anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            return reviewedNumericResult(question, question.contains("2025年")
                    ? "2025年营收：100亿元" : "2026年营收：120亿元");
        });
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n2025年营收：100亿元。"),
                        new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n2026年营收：120亿元。"));

        assertEquals(2, fixture.service.executeDebateRound(1L, 1).size());
        assertEquals(2, fixture.inserted.size());
    }

    @Test
    void sameMetricAndYearForDifferentStocksDoNotConflict() {
        Fixture fixture = new Fixture("两只股票的营收比较", 2);
        fixture.plan(1, webNeedForStock("revenue1", "2025年公司甲营收是多少？", "300750"));
        fixture.plan(2, webNeedForStock("revenue2", "2025年公司乙营收是多少？", "600519"));
        when(fixture.reviewed.search(anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            return reviewedNumericResult(question, question.contains("公司甲")
                    ? "2025年营收：100亿元" : "2025年营收：120亿元");
        });
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n2025年营收：100亿元。"),
                        new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n2025年营收：120亿元。"));

        assertEquals(2, fixture.service.executeDebateRound(1L, 1).size());
        assertEquals(2, fixture.inserted.size());
    }

    @Test
    void explicitlyDisclosedVerifiedDisagreementIsRetainedInEvidence() {
        Fixture fixture = new Fixture("宁德时代未来三个月价格走势", 2);
        fixture.plan(1, webNeedForStock("revenue1", "2025年宁德时代营收初始公告是多少？", "300750"));
        fixture.plan(2, webNeedForStock("revenue2", "2025年宁德时代营收修订公告是多少？", "300750"));
        when(fixture.reviewed.search(anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            return reviewedNumericResult(question, question.contains("初始")
                    ? "2025年营收：100亿元" : "2025年营收：120亿元");
        });
        when(fixture.orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                .thenReturn(new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n2025年营收：100亿元。"),
                        new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n前序议员对2025年营收的数字"
                                + "与本轮复核不同；本轮2025年营收：120亿元。"));

        assertEquals(2, fixture.service.executeDebateRound(1L, 1).size());
        JSONObject evidence = JSON.parseObject(fixture.inserted.get(1).getEvidence());
        assertTrue(evidence.containsKey("reviewedCrossMemberConflict"), evidence.toJSONString());
        assertEquals("DISCLOSED", evidence.getJSONObject("reviewedCrossMemberConflict").getString("status"));
        assertEquals(1, evidence.getJSONObject("reviewedCrossMemberConflict").getIntValue("conflictCount"));
    }

    @Test
    void targetedQuestionLimitsReviewedRoundToItsMember() {
        Fixture fixture = new Fixture("人工智能算力市场", 2);
        AiMeetingOpinion target = new AiMeetingOpinion();
        target.setId(99L);
        target.setMeetingId(1L);
        target.setOpinionType(OpinionTypeEnum.HUMAN_TARGETED.getCode());
        target.setEvidence("{\"targetMemberId\":2}");
        fixture.inserted.add(target);
        fixture.plan(2, "{\"need\":false,\"needs\":[]}");

        List<AiMeetingOpinion> opinions = fixture.service.executeDebateRound(1L, 1);

        assertEquals(1, opinions.size());
        assertEquals(2L, opinions.get(0).getMemberId());
        assertEquals(99L, opinions.get(0).getParentOpinionId());
        verify(fixture.orchestrator, never()).buildReviewedEvidenceNeedsPrompt(1L, 1L, 1);
    }

    private static String webNeed(String id, String question) {
        return "{\"need\":true,\"needs\":[{\"id\":\"" + id + "\",\"question\":\""
                + question + "\",\"eastmoneyFields\":[],\"webSearch\":true,\"sourceFocus\":[]}]}";
    }

    private static String webNeedForStock(String id, String question, String symbol) {
        return "{\"need\":true,\"eastmoneySymbol\":\"" + symbol
                + "\",\"eastmoneyMarket\":\"sz\",\"needs\":[{\"id\":\"" + id
                + "\",\"question\":\"" + question
                + "\",\"eastmoneyFields\":[],\"webSearch\":true,\"webQuestion\":\""
                + question + "\",\"sourceFocus\":[]}]}";
    }

    private static MeetingReviewedEvidenceService.ReviewedEvidence reviewedNumericResult(
            String question, String statement) {
        JSONObject packet = JSON.parseObject(reviewedResult(question).packet.toJSONString());
        packet.getJSONArray("claims").getJSONObject(0).put("statement", statement);
        return MeetingReviewedEvidenceService.acceptPacket(packet, "member-test", question);
    }

    private static MeetingReviewedEvidenceService.ReviewedEvidence reviewedResult(String question) {
        JSONObject packet = JSON.parseObject("{\"schemaVersion\":\"general.evidence-search.v1\","
                + "\"requestId\":\"member-test\",\"status\":\"CROSS_CHECKED\","
                + "\"audit\":{\"reviewCalls\":1,\"trail\":["
                + "{\"phase\":\"search\",\"status\":\"FOUND\",\"searchCount\":1,"
                + "\"ids\":[\"fact\"],\"links\":[\"https://example.org/a\"]},"
                + "{\"phase\":\"review\",\"id\":\"fact\",\"verdict\":\"PASS\","
                + "\"reviewerVerdict\":\"PASS\",\"searchCount\":1,"
                + "\"links\":[\"https://example.org/b\"]}]},\"claims\":[{\"id\":\"fact\","
                + "\"statement\":\"已复核的公开事实\",\"sourceUrls\":[\"https://example.org/a\"],"
                + "\"reviewLinks\":[\"https://example.org/b\"],"
                + "\"reviewMethod\":\"TWO_ISOLATED_DEEPSEEK_SEARCHES\"}]}");
        packet.put("question", question);
        return MeetingReviewedEvidenceService.acceptPacket(packet, "member-test", question);
    }

    private static final class Fixture {
        private final MeetingDebateService service = new MeetingDebateService();
        private final AiMeetingRoomMapper roomMapper = mock(AiMeetingRoomMapper.class);
        private final AiMeetingMemberMapper memberMapper = mock(AiMeetingMemberMapper.class);
        private final AiMeetingOpinionMapper opinionMapper = mock(AiMeetingOpinionMapper.class);
        private final MeetingWorkflowService workflow = mock(MeetingWorkflowService.class);
        private final MeetingReviewedEvidenceService reviewed = mock(MeetingReviewedEvidenceService.class);
        private final MeetingOrchestratorService orchestrator = mock(MeetingOrchestratorService.class);
        private final WebSearchService webSearch = mock(WebSearchService.class);
        private final EastmoneyMarketDataService eastmoney = mock(EastmoneyMarketDataService.class);
        private final List<AiMeetingOpinion> inserted = new ArrayList<>();
        private final Map<String, String> decisions = new HashMap<>();
        private final Map<Long, MeetingReviewedTurnSkip> skipped = new HashMap<>();

        private Fixture(String title, int count) {
            AiMeetingRoom room = new AiMeetingRoom();
            room.setId(1L);
            room.setTitle(title);
            room.setStatus(MeetingStatusEnum.IN_PROGRESS.getCode());
            List<AiMeetingMember> members = new ArrayList<>();
            for (long id = 1; id <= count; id++) {
                AiMeetingMember member = new AiMeetingMember();
                member.setId(id);
                member.setMeetingId(1L);
                member.setMemberName("议员" + id);
                member.setModelType("deepseek-chat");
                member.setPersona("视角" + id);
                members.add(member);
            }
            when(roomMapper.selectById(1L)).thenReturn(room);
            when(memberMapper.selectByMeetingId(1L)).thenReturn(members);
            when(workflow.resolveMembersForCurrentStage(eq(1L), anyList())).thenReturn(members);
            when(opinionMapper.selectByMeetingIdAndRound(1L, 1)).thenReturn(List.of());
            when(opinionMapper.selectByMeetingId(1L)).thenAnswer(invocation -> new ArrayList<>(inserted));
            when(opinionMapper.insert(any())).thenAnswer(invocation -> {
                AiMeetingOpinion opinion = invocation.getArgument(0);
                opinion.setId(100L + inserted.size());
                inserted.add(opinion);
                return 1;
            });
            when(reviewed.isEnabled()).thenReturn(true);
            when(orchestrator.buildReviewedEvidenceNeedsPrompt(eq(1L), anyLong(), eq(1)))
                    .thenAnswer(invocation -> "plan-" + invocation.getArgument(1));
            when(orchestrator.callModelStrict(eq("deepseek-chat"), anyString(), anyString(), eq(500)))
                    .thenAnswer(invocation -> decisions.get(invocation.getArgument(2)));
            when(orchestrator.buildDebateContext(eq(1L), anyLong(), eq(1), nullable(String.class)))
                    .thenAnswer(invocation -> "context-" + invocation.getArgument(1));
            when(orchestrator.callModelExStrict(eq("deepseek-chat"), anyString(), anyString()))
                    .thenReturn(new ChatResult("{\"q\":[],\"e\":[]}\n==REPLY==\n本轮观点。"));
            when(webSearch.formatResultsForPrompt(anyList())).thenReturn("已审核资料");
            ReflectionTestUtils.setField(service, "roomMapper", roomMapper);
            ReflectionTestUtils.setField(service, "memberMapper", memberMapper);
            ReflectionTestUtils.setField(service, "opinionMapper", opinionMapper);
            ReflectionTestUtils.setField(service, "workflowService", workflow);
            ReflectionTestUtils.setField(service, "reviewedEvidenceService", reviewed);
            ReflectionTestUtils.setField(service, "orchestrator", orchestrator);
            ReflectionTestUtils.setField(service, "webSearch", webSearch);
            ReflectionTestUtils.setField(service, "eastmoneyMarketDataService", eastmoney);
            ReflectionTestUtils.setField(service, "stockWatchItemMapper", mock(StockWatchItemMapper.class));
            ReflectionTestUtils.setField(service, "modelRegistryStore", mock(ModelRegistryStore.class));
        }

        private void bundle(String field, Object value) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("symbol", "300750");
            raw.put("market", "sz");
            raw.put(field, value);
            raw.put("dataQuality", Map.of("sourceStatus", Map.of(field, Map.of("ok", true))));
            when(eastmoney.buildBundle("300750", "sz", "DAY"))
                    .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));
        }

        private void completedBarBundle(String captureTime) {
            completedBarBundle(captureTime, Map.of("ma20", 290));
        }

        private void completedBarBundle(String captureTime, Map<String, Object> indicators) {
            Map<String, Object> raw = new LinkedHashMap<>();
            raw.put("symbol", "300750");
            raw.put("market", "sz");
            LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            raw.put("asOf", date + "T" + captureTime + "+08:00");
            List<Map<String, Object>> bars = new ArrayList<>();
            for (int i = 0; i < 40; i++) bars.add(Map.of("date", date.minusDays(39L - i).toString(), "close", 290));
            raw.put("dailyKline", bars);
            raw.put("technicalIndicators", indicators);
            raw.put("sourceTradeDateQuality", Map.of("consistent", true,
                    "quoteTradeDate", date.toString(), "dailyKlineTradeDate", date.toString()));
            raw.put("dataQuality", Map.of("sourceStatus", Map.of("dailyKline", Map.of("ok", true))));
            when(eastmoney.buildBundle("300750", "sz", "DAY"))
                    .thenReturn(new EastmoneyMarketDataService.MarketDataBundle(raw, List.of(), List.of()));
        }

        private void plan(long id, String response) {
            decisions.put("plan-" + id, response);
        }

        private void reviewSuccess() {
            when(reviewed.search(anyString())).thenAnswer(invocation ->
                    reviewedResult(invocation.getArgument(0)));
        }

        private MeetingReviewedTurnSkipService enableSkips() {
            MeetingReviewedTurnSkipService skips = mock(MeetingReviewedTurnSkipService.class);
            when(skips.findSkip(eq(1L), eq(1), anyLong(), nullable(Long.class)))
                    .thenAnswer(invocation -> skipped.get(invocation.getArgument(2)));
            when(skips.recordIfCurrent(any(MeetingReviewedTurnSkip.class),
                    eq(MeetingStatusEnum.IN_PROGRESS.getCode()), nullable(Long.class)))
                    .thenAnswer(invocation -> {
                        MeetingReviewedTurnSkip skip = invocation.getArgument(0);
                        skipped.put(skip.getMemberId(), skip);
                        return skip;
                    });
            when(opinionMapper.selectByMeetingIdAndRound(1L, 1)).thenAnswer(invocation ->
                    inserted.stream().filter(opinion -> Integer.valueOf(1).equals(opinion.getRoundNumber()))
                            .collect(Collectors.toList()));
            ReflectionTestUtils.setField(service, "reviewedTurnSkipService", skips);
            return skips;
        }
    }
}
