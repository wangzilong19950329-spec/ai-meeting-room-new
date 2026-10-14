package com.aimeeting.room.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aimeeting.room.dao.StockWatchFactObservationMapper;
import com.aimeeting.room.dao.StockWatchPriceFailureAssessmentMapper;
import com.aimeeting.room.dao.StockWatchPriceFailureLedgerMapper;
import com.aimeeting.room.dao.StockWatchPriceFailureResearchStateMapper;
import com.aimeeting.room.entity.StockWatchFactObservation;
import com.aimeeting.room.entity.StockWatchPriceFailureAssessment;
import com.aimeeting.room.entity.StockWatchPriceFailureLedger;
import com.aimeeting.room.entity.StockWatchPriceFailureResearchState;
import com.aimeeting.room.service.StockWatchPriceFailureResearchRule.RuleAssessment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class StockWatchPriceFailureResearchService {

    private static final ZoneId CHINA_ZONE = ZoneId.of("Asia/Shanghai");

    private final StockWatchFactObservationMapper factObservationMapper;
    private final StockWatchPriceFailureResearchStateMapper stateMapper;
    private final StockWatchPriceFailureAssessmentMapper assessmentMapper;
    private final StockWatchPriceFailureLedgerMapper ledgerMapper;
    private final StockWatchPriceFailureResearchRule rule;

    @Value("${stock-watch.price-failure-research.enabled:true}")
    private boolean enabled = true;

    @Value("${stock-watch.price-failure-research.batch-limit:500}")
    private int batchLimit = 500;

    @Value("${stock-watch.price-failure-research.reconcile-limit:200}")
    private int reconcileLimit = 200;

    @Value("${stock-watch.price-failure-research.outcome-observation-limit:1000}")
    private int outcomeObservationLimit = 1000;

    @EventListener(ApplicationReadyEvent.class)
    public void initializeProspectiveCursor() {
        if (!enabled) return;
        try {
            verifyRuleSpec(ensureState());
        } catch (Exception e) {
            log.error("stock-watch price-failure research bootstrap failed: {}", e.getMessage(), e);
        }
    }

    @Scheduled(fixedDelayString = "${stock-watch.price-failure-research.scan-interval-ms:60000}",
            initialDelayString = "${stock-watch.price-failure-research.initial-delay-ms:120000}")
    public void scheduledRun() {
        if (!enabled) return;
        try {
            Map<String, Object> result = runOnce();
            if (((Number) result.get("processedObservations")).intValue() > 0) {
                log.info("stock-watch price-failure research run {}", result);
            }
        } catch (Exception e) {
            log.error("stock-watch price-failure research run failed: {}", e.getMessage(), e);
        }
    }

    public Map<String, Object> runOnce() {
        StockWatchPriceFailureResearchState state = ensureState();
        verifyRuleSpec(state);
        long lastProcessed = value(state.getLastObservationId(), 0L);
        List<StockWatchFactObservation> observations = factObservationMapper.selectAfterId(
                lastProcessed, bounded(batchLimit, 1, 2000));
        int assessmentCount = 0;
        int signalCount = 0;
        for (StockWatchFactObservation observation : observations) {
            StockWatchPriceFailureAssessment assessment = processObservation(observation);
            assessmentCount++;
            if (ensureSignal(assessment) != null && Boolean.TRUE.equals(assessment.getPriceShapeTriggered())) {
                signalCount++;
            }
            lastProcessed = observation.getId();
        }
        if (!observations.isEmpty()) {
            int updated = stateMapper.updateCursor(state.getRuleVersion(), value(state.getRowVersion(), 0L), lastProcessed);
            if (updated != 1) throw new IllegalStateException("price_failure_state_concurrent_update");
        }
        int controls = reconcileControls();
        int outcomes = refreshPendingOutcomes();

        Map<String, Object> result = progress();
        result.put("processedObservations", assessmentCount);
        result.put("signalChecks", signalCount);
        result.put("controlsCreated", controls);
        result.put("outcomesUpdated", outcomes);
        result.put("lastObservationId", lastProcessed);
        return result;
    }

    private StockWatchPriceFailureAssessment processObservation(StockWatchFactObservation observation) {
        StockWatchPriceFailureAssessment existing = existingAssessment(observation);
        if (existing != null) return existing;
        long evaluationAt = System.currentTimeMillis();
        RuleAssessment evaluated = rule.evaluate(observation, evaluationAt);
        long completedAt = System.currentTimeMillis();
        if (!sameChinaDate(evaluationAt, completedAt)) {
            evaluated = rule.evaluate(observation, completedAt);
            completedAt = System.currentTimeMillis();
        }
        return persistAssessment(observation, evaluated, completedAt);
    }

    StockWatchPriceFailureAssessment processObservationAt(StockWatchFactObservation observation,
                                                           long completedAtEpochMs) {
        StockWatchPriceFailureAssessment existing = existingAssessment(observation);
        if (existing != null) return existing;
        RuleAssessment evaluated = rule.evaluate(observation, completedAtEpochMs);
        return persistAssessment(observation, evaluated, completedAtEpochMs);
    }

    private StockWatchPriceFailureAssessment existingAssessment(StockWatchFactObservation observation) {
        return assessmentMapper.selectByRuleObservation(
                StockWatchPriceFailureResearchRule.RULE_VERSION, observation.getId());
    }

    private StockWatchPriceFailureAssessment persistAssessment(StockWatchFactObservation observation,
                                                               RuleAssessment evaluated,
                                                               long completedAtEpochMs) {
        long availableTime = Math.max(value(observation.getSourceTimeEpochMs(), 0L), completedAtEpochMs);
        StockWatchPriceFailureAssessment assessment = new StockWatchPriceFailureAssessment();
        assessment.setRuleVersion(StockWatchPriceFailureResearchRule.RULE_VERSION);
        assessment.setRuleSpecHash(rule.getRuleSpecHash());
        assessment.setObservationId(observation.getId());
        assessment.setSymbol(observation.getSymbol());
        assessment.setMarket(observation.getMarket());
        assessment.setStockName(observation.getStockName());
        assessment.setSourceDate(observation.getSourceDate());
        assessment.setSourceTimeEpochMs(observation.getSourceTimeEpochMs());
        assessment.setAvailableTimeEpochMs(availableTime);
        assessment.setEvaluatedAt(new Date(completedAtEpochMs));
        assessment.setEligible(evaluated.isEligible());
        assessment.setHighPosition(evaluated.isHighPosition());
        assessment.setActiveTurnover(evaluated.isActiveTurnover());
        assessment.setCurrentNotCollapsed(evaluated.isCurrentNotCollapsed());
        assessment.setPriceShapeTriggered(evaluated.isPriceShapeTriggered());
        assessment.setFlowDataComplete(evaluated.isFlowDataComplete());
        assessment.setStrongOutflow(evaluated.isStrongOutflow());
        assessment.setShapeCode(evaluated.getShapeCode());
        assessment.setStratum(evaluated.getStratum());
        assessment.setSignalPrice(evaluated.getSignalPrice());
        Map<String, Object> frozenFeatures = new LinkedHashMap<>(evaluated.getFeatures());
        frozenFeatures.put("ruleCompletedAtEpochMs", completedAtEpochMs);
        assessment.setFeatureSnapshotJson(JSON.toJSONString(frozenFeatures));
        assessment.setExclusionReason(evaluated.getExclusionReason());
        try {
            assessmentMapper.insert(assessment);
            return assessment;
        } catch (DuplicateKeyException raced) {
            StockWatchPriceFailureAssessment winner = assessmentMapper.selectByRuleObservation(
                    StockWatchPriceFailureResearchRule.RULE_VERSION, observation.getId());
            if (winner != null) return winner;
            throw raced;
        }
    }

    private StockWatchPriceFailureResearchState ensureState() {
        String version = StockWatchPriceFailureResearchRule.RULE_VERSION;
        StockWatchPriceFailureResearchState existing = stateMapper.selectByRuleVersion(version);
        if (existing != null) return existing;
        long currentMax = value(factObservationMapper.selectMaxId(), 0L);
        StockWatchPriceFailureResearchState state = new StockWatchPriceFailureResearchState();
        state.setRuleVersion(version);
        state.setRuleSpecHash(rule.getRuleSpecHash());
        state.setRuleSpecJson(rule.getRuleSpecJson());
        state.setBootstrapObservationId(currentMax);
        state.setLastObservationId(currentMax);
        state.setRowVersion(0L);
        state.setStartedAt(new Date());
        try {
            stateMapper.insert(state);
            log.info("stock-watch price-failure research bootstrapped ruleVersion={} observationId={} noBackfill=true",
                    version, currentMax);
            return state;
        } catch (DuplicateKeyException raced) {
            StockWatchPriceFailureResearchState winner = stateMapper.selectByRuleVersion(version);
            if (winner != null) return winner;
            throw raced;
        }
    }

    private void verifyRuleSpec(StockWatchPriceFailureResearchState state) {
        if (!rule.getRuleSpecHash().equals(state.getRuleSpecHash())
                || !rule.getRuleSpecJson().equals(state.getRuleSpecJson())) {
            throw new IllegalStateException("price_failure_rule_spec_changed_without_new_version");
        }
    }

    private StockWatchPriceFailureLedger ensureSignal(StockWatchPriceFailureAssessment assessment) {
        if (assessment == null || !Boolean.TRUE.equals(assessment.getEligible())
                || !Boolean.TRUE.equals(assessment.getPriceShapeTriggered())) return null;
        StockWatchPriceFailureLedger existing = ledgerMapper.selectSignalBySymbolDate(
                assessment.getRuleVersion(), assessment.getSymbol(), assessment.getMarket(), assessment.getSourceDate());
        if (existing != null) return existing;
        StockWatchPriceFailureLedger signal = ledgerFromAssessment(assessment, "SIGNAL", null, null);
        signal.setSignalDayKey(assessment.getRuleVersion() + "|" + assessment.getSymbol() + "|"
                + assessment.getMarket() + "|" + assessment.getSourceDate());
        signal.setSampleKey(sha256(assessment.getRuleVersion() + "|SIGNAL|" + assessment.getObservationId()));
        try {
            ledgerMapper.insert(signal);
            return signal;
        } catch (DuplicateKeyException raced) {
            return ledgerMapper.selectSignalBySymbolDate(assessment.getRuleVersion(), assessment.getSymbol(),
                    assessment.getMarket(), assessment.getSourceDate());
        }
    }

    private int reconcileControls() {
        List<StockWatchPriceFailureLedger> signals = ledgerMapper.selectSignalsNeedingControls(
                StockWatchPriceFailureResearchRule.RULE_VERSION,
                bounded(reconcileLimit, 1, 1000));
        int created = 0;
        String maximumObservedDate = factObservationMapper.selectMaxSourceDate();
        for (StockWatchPriceFailureLedger signal : signals) {
            List<StockWatchPriceFailureLedger> existing = ledgerMapper.selectControlsBySignal(signal.getId());
            int needed = StockWatchPriceFailureResearchRule.MAX_CONTROLS - existing.size();
            if (needed <= 0) continue;
            Set<Long> usedObservationIds = new HashSet<>();
            for (StockWatchPriceFailureLedger item : existing) usedObservationIds.add(item.getOriginObservationId());
            List<StockWatchPriceFailureAssessment> raw = assessmentMapper.selectControlCandidates(
                    signal.getRuleVersion(), signal.getMarket(), signal.getSourceDate(),
                    Math.max(0L, signal.getSourceTimeEpochMs() - StockWatchPriceFailureResearchRule.CONTROL_TIME_WINDOW_MS),
                    Math.min(signal.getAvailableTimeEpochMs(),
                            signal.getSourceTimeEpochMs() + StockWatchPriceFailureResearchRule.CONTROL_TIME_WINDOW_MS),
                    signal.getAvailableTimeEpochMs());
            Map<String, StockWatchPriceFailureAssessment> closestBySymbol = new LinkedHashMap<>();
            for (StockWatchPriceFailureAssessment candidate : raw) {
                if (signal.getSymbol().equals(candidate.getSymbol()) || usedObservationIds.contains(candidate.getObservationId())) {
                    continue;
                }
                String key = candidate.getSymbol() + "." + candidate.getMarket();
                StockWatchPriceFailureAssessment current = closestBySymbol.get(key);
                if (current == null || timeDistance(candidate, signal) < timeDistance(current, signal)) {
                    closestBySymbol.put(key, candidate);
                }
            }
            List<ControlCandidate> ranked = new ArrayList<>();
            for (StockWatchPriceFailureAssessment candidate : closestBySymbol.values()) {
                Double score = matchScore(signal, candidate);
                if (score != null) ranked.add(new ControlCandidate(candidate, score));
            }
            ranked.sort(Comparator.comparingDouble(ControlCandidate::getScore));
            for (ControlCandidate item : ranked) {
                if (needed <= 0) break;
                StockWatchPriceFailureLedger control = ledgerFromAssessment(
                        item.getAssessment(), "CONTROL", signal.getId(), item.getScore());
                control.setSampleKey(sha256(signal.getRuleVersion() + "|CONTROL|" + signal.getId()
                        + "|" + item.getAssessment().getObservationId()));
                control.setAvailableTimeEpochMs(signal.getAvailableTimeEpochMs());
                control.setAvailableAt(signal.getAvailableAt());
                try {
                    ledgerMapper.insert(control);
                    created++;
                    needed--;
                } catch (DuplicateKeyException ignored) {
                    // Deterministic sample key makes retries harmless.
                }
            }
            int finalControlCount = ledgerMapper.selectControlsBySignal(signal.getId()).size();
            boolean sourceWindowClosed = maximumObservedDate != null
                    && maximumObservedDate.compareTo(signal.getSourceDate()) > 0;
            if (finalControlCount >= StockWatchPriceFailureResearchRule.MAX_CONTROLS || sourceWindowClosed) {
                ledgerMapper.markControlsFinalized(signal.getId(), value(signal.getRowVersion(), 0L));
            }
        }
        return created;
    }

    private StockWatchPriceFailureLedger ledgerFromAssessment(StockWatchPriceFailureAssessment assessment,
                                                               String role,
                                                               Long matchedSignalId,
                                                               Double matchScore) {
        StockWatchPriceFailureLedger row = new StockWatchPriceFailureLedger();
        row.setRuleVersion(assessment.getRuleVersion());
        row.setRuleSpecHash(assessment.getRuleSpecHash());
        row.setSampleRole(role);
        row.setMatchedSignalId(matchedSignalId);
        row.setOriginAssessmentId(assessment.getId());
        row.setOriginObservationId(assessment.getObservationId());
        row.setSymbol(assessment.getSymbol());
        row.setMarket(assessment.getMarket());
        row.setStockName(assessment.getStockName());
        row.setStratum("SIGNAL".equals(role) ? assessment.getStratum() : "MATCHED_CONTROL");
        row.setSourceDate(assessment.getSourceDate());
        row.setSourceTimeEpochMs(assessment.getSourceTimeEpochMs());
        row.setAvailableTimeEpochMs(assessment.getAvailableTimeEpochMs());
        row.setAvailableAt(new Date(assessment.getAvailableTimeEpochMs()));
        row.setSignalPrice(assessment.getSignalPrice());
        row.setFeatureSnapshotJson(assessment.getFeatureSnapshotJson());
        row.setMatchScore(matchScore);
        row.setControlsFinalized(!"SIGNAL".equals(role));
        row.setStatus("WAITING_ENTRY");
        row.setRowVersion(0L);
        return row;
    }

    private Double matchScore(StockWatchPriceFailureLedger signal,
                              StockWatchPriceFailureAssessment control) {
        JSONObject left = parse(signal.getFeatureSnapshotJson());
        JSONObject right = parse(control.getFeatureSnapshotJson());
        if (left == null || right == null) return null;
        Double daily = scaledDistance(left, right, "dailyReturnPct", 2.0);
        Double return5 = scaledDistance(left, right, "return5Pct", 5.0);
        Double volatility = scaledDistance(left, right, "volatility20Pct", 2.0);
        Double position = scaledDistance(left, right, "closePosition20dPct", 10.0);
        Double turnover = scaledDistance(left, right, "turnoverPct", 2.0);
        Double leftAmount = number(left.get("amount"));
        Double rightAmount = number(right.get("amount"));
        if (daily == null || return5 == null || volatility == null || position == null || turnover == null
                || leftAmount == null || rightAmount == null || leftAmount <= 0 || rightAmount <= 0) return null;
        double liquidity = Math.abs(Math.log(leftAmount / rightAmount));
        double time = timeDistance(control, signal) / (double) StockWatchPriceFailureResearchRule.CONTROL_TIME_WINDOW_MS;
        return round(daily + return5 + volatility + position + turnover + liquidity + time);
    }

    private int refreshPendingOutcomes() {
        List<StockWatchPriceFailureLedger> rows = ledgerMapper.selectPending(
                StockWatchPriceFailureResearchRule.RULE_VERSION, bounded(reconcileLimit, 1, 1000));
        int updated = 0;
        Map<String, String> targetDateCache = new LinkedHashMap<>();
        String maximumObservedDate = factObservationMapper.selectMaxSourceDate();
        for (StockWatchPriceFailureLedger row : rows) {
            String targetDate = targetDateCache.computeIfAbsent(row.getSourceDate(),
                    factObservationMapper::selectNextObservedSourceDate);
            List<StockWatchFactObservation> facts = factObservationMapper.selectForOutcome(
                    row.getSymbol(), row.getMarket(), row.getAvailableTimeEpochMs(),
                    bounded(outcomeObservationLimit, 10, 5000));
            Outcome outcome = calculateOutcome(row, targetDate, maximumObservedDate, facts);
            if (!outcome.changed) continue;
            int count = ledgerMapper.updateOutcome(row.getId(), value(row.getRowVersion(), 0L), outcome.status,
                    outcome.entryObservationId, outcome.entrySourceTimeEpochMs, outcome.entrySourceDate,
                    outcome.entryPrice, outcome.t1TradeDate, outcome.t1Open, outcome.t1High, outcome.t1Low,
                    outcome.t1Close, outcome.maxDrawdownPct, outcome.closeReturnPct, outcome.gapPct,
                    outcome.maxUpsidePct, outcome.drawdown5Hit, outcome.dataQualityReason, outcome.maturedAt);
            if (count == 1) updated++;
        }
        return updated;
    }

    private Outcome calculateOutcome(StockWatchPriceFailureLedger row,
                                     String targetDate,
                                     String maximumObservedDate,
                                     List<StockWatchFactObservation> facts) {
        Outcome result = new Outcome();
        result.status = row.getStatus();
        boolean targetPostCloseSeen = targetDate != null && facts.stream().anyMatch(fact ->
                targetDate.equals(fact.getSourceDate())
                        && "POST_CLOSE_VALID".equals(fact.getSourceTimeQuality()));
        Long entryObservationId = row.getEntryObservationId();
        Long entrySourceTimeEpochMs = row.getEntrySourceTimeEpochMs();
        String entrySourceDate = row.getEntrySourceDate();
        Double entryPrice = row.getEntryPrice();
        boolean entryRecorded = entryObservationId != null;
        if (!entryRecorded) {
            boolean nextSessionOpenRequired = nextSessionOpenRequired(row);
            for (StockWatchFactObservation fact : facts) {
                boolean sameSourceDate = row.getSourceDate().equals(fact.getSourceDate());
                if (nextSessionOpenRequired && sameSourceDate) continue;
                Double price = quoteNumber(fact, sameSourceDate ? "price" : "open");
                if (price != null && price > 0) {
                    entryObservationId = fact.getId();
                    entrySourceTimeEpochMs = fact.getSourceTimeEpochMs();
                    entrySourceDate = fact.getSourceDate();
                    entryPrice = price;
                    break;
                }
            }
        }
        if (entryObservationId == null) {
            if (targetPostCloseSeen || (targetDate != null && maximumObservedDate != null
                    && maximumObservedDate.compareTo(targetDate) > 0)) {
                result.status = "DATA_QUALITY_FAILED";
                result.dataQualityReason = "EXECUTABLE_PROXY_MISSING_BEFORE_T1_END";
                result.maturedAt = new Date();
                result.changed = true;
            }
            return result;
        }
        if (entrySourceTimeEpochMs == null || entrySourceDate == null || entryPrice == null || entryPrice <= 0) {
            result.status = "DATA_QUALITY_FAILED";
            result.dataQualityReason = "RECORDED_ENTRY_INCOMPLETE";
            result.maturedAt = new Date();
            result.changed = true;
            return result;
        }
        result.changed = !entryRecorded;
        result.entryObservationId = entryObservationId;
        result.entrySourceTimeEpochMs = entrySourceTimeEpochMs;
        result.entrySourceDate = entrySourceDate;
        result.entryPrice = entryPrice;
        result.status = "WAITING_T1";
        if (targetDate == null) return result;
        if (entrySourceDate.compareTo(targetDate) > 0) {
            result.status = "DATA_QUALITY_FAILED";
            result.dataQualityReason = "EXECUTABLE_PROXY_MISSING_BEFORE_T1_END";
            result.maturedAt = new Date();
            result.changed = true;
            return result;
        }

        StockWatchFactObservation postCloseFact = null;
        for (StockWatchFactObservation fact : facts) {
            if (!targetDate.equals(fact.getSourceDate())
                    || !"POST_CLOSE_VALID".equals(fact.getSourceTimeQuality())) continue;
            long factSourceTime = value(fact.getSourceTimeEpochMs(), Long.MIN_VALUE);
            long selectedSourceTime = postCloseFact == null ? Long.MIN_VALUE
                    : value(postCloseFact.getSourceTimeEpochMs(), Long.MIN_VALUE);
            if (postCloseFact == null || factSourceTime > selectedSourceTime
                    || (factSourceTime == selectedSourceTime
                    && value(fact.getId(), 0L) > value(postCloseFact.getId(), 0L))) {
                postCloseFact = fact;
            }
        }
        result.t1TradeDate = targetDate;
        boolean globalLaterDateSeen = maximumObservedDate != null && maximumObservedDate.compareTo(targetDate) > 0;
        if (postCloseFact == null) {
            if (globalLaterDateSeen) {
                result.status = "DATA_QUALITY_FAILED";
                result.dataQualityReason = "T1_POST_CLOSE_SNAPSHOT_MISSING";
                result.maturedAt = new Date();
                result.changed = true;
                return result;
            }
            return result;
        }
        result.t1Open = quoteNumber(postCloseFact, "open");
        result.t1High = quoteNumber(postCloseFact, "high");
        result.t1Low = quoteNumber(postCloseFact, "low");
        result.t1Close = quoteNumber(postCloseFact, "price");
        if (result.t1Open == null || result.t1High == null || result.t1Low == null || result.t1Close == null) {
            result.status = "DATA_QUALITY_FAILED";
            result.dataQualityReason = "T1_OHLC_INCOMPLETE";
            result.maturedAt = new Date();
            result.changed = true;
            return result;
        }
        result.maxDrawdownPct = returnPct(result.t1Low, entryPrice);
        result.closeReturnPct = returnPct(result.t1Close, entryPrice);
        result.gapPct = returnPct(result.t1Open, row.getSignalPrice());
        result.maxUpsidePct = returnPct(result.t1High, entryPrice);
        result.drawdown5Hit = result.maxDrawdownPct != null && result.maxDrawdownPct <= -5.0;
        result.status = "MATURE";
        result.maturedAt = new Date();
        result.changed = true;
        return result;
    }

    public Map<String, Object> progress() {
        int sourceDays = assessmentMapper.countDistinctSourceDates(StockWatchPriceFailureResearchRule.RULE_VERSION);
        int matureSignals = ledgerMapper.countMatureSignals(StockWatchPriceFailureResearchRule.RULE_VERSION);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ruleVersion", StockWatchPriceFailureResearchRule.RULE_VERSION);
        result.put("ruleSpecHash", rule.getRuleSpecHash());
        result.put("sourceDateCount", sourceDays);
        result.put("matureSignalCount", matureSignals);
        result.put("minimumSourceDates", StockWatchPriceFailureResearchRule.MIN_UNBLIND_SOURCE_DAYS);
        result.put("minimumMatureSignals", StockWatchPriceFailureResearchRule.MIN_UNBLIND_MATURE_SIGNALS);
        result.put("unblindingEligible", sourceDays >= StockWatchPriceFailureResearchRule.MIN_UNBLIND_SOURCE_DAYS
                && matureSignals >= StockWatchPriceFailureResearchRule.MIN_UNBLIND_MATURE_SIGNALS);
        result.put("performanceMetrics", "BLINDED");
        return result;
    }

    private JSONObject parse(String json) {
        try {
            return JSON.parseObject(json);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean nextSessionOpenRequired(StockWatchPriceFailureLedger row) {
        JSONObject features = parse(row.getFeatureSnapshotJson());
        if (features != null && "POST_CLOSE_VALID".equals(features.getString("sourceTimeQuality"))) {
            return true;
        }
        try {
            LocalDate sourceDate = LocalDate.parse(row.getSourceDate());
            java.time.ZonedDateTime available = Instant.ofEpochMilli(row.getAvailableTimeEpochMs())
                    .atZone(CHINA_ZONE);
            return available.toLocalDate().isAfter(sourceDate)
                    || (!available.toLocalDate().isBefore(sourceDate)
                    && !available.toLocalTime().isBefore(LocalTime.of(15, 0)));
        } catch (Exception invalidTime) {
            return true;
        }
    }

    private boolean sameChinaDate(long leftEpochMs, long rightEpochMs) {
        return Instant.ofEpochMilli(leftEpochMs).atZone(CHINA_ZONE).toLocalDate()
                .equals(Instant.ofEpochMilli(rightEpochMs).atZone(CHINA_ZONE).toLocalDate());
    }

    private Double quoteNumber(StockWatchFactObservation fact, String key) {
        try {
            JSONObject snapshot = JSON.parseObject(fact.getFactSnapshotJson());
            JSONObject quote = snapshot == null ? null : snapshot.getJSONObject("quote");
            return number(quote == null ? null : quote.get(key));
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double scaledDistance(JSONObject left, JSONObject right, String key, double scale) {
        Double first = number(left.get(key));
        Double second = number(right.get(key));
        return first == null || second == null ? null : Math.abs(first - second) / scale;
    }

    private long timeDistance(StockWatchPriceFailureAssessment assessment, StockWatchPriceFailureLedger signal) {
        return Math.abs(value(assessment.getSourceTimeEpochMs(), 0L) - value(signal.getSourceTimeEpochMs(), 0L));
    }

    private Double number(Object value) {
        if (value == null) return null;
        try {
            double number = value instanceof Number ? ((Number) value).doubleValue()
                    : Double.parseDouble(String.valueOf(value));
            return Double.isFinite(number) ? number : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private Double returnPct(Double value, Double base) {
        return value == null || base == null || base <= 0 ? null : round((value / base - 1.0) * 100.0);
    }

    private double round(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private int bounded(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private long value(Long value, long fallback) {
        return value == null ? fallback : value;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : bytes) result.append(String.format("%02x", item));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("price_failure_sample_key_hash_failed", e);
        }
    }

    private static class ControlCandidate {
        private final StockWatchPriceFailureAssessment assessment;
        private final double score;

        private ControlCandidate(StockWatchPriceFailureAssessment assessment, double score) {
            this.assessment = assessment;
            this.score = score;
        }

        StockWatchPriceFailureAssessment getAssessment() { return assessment; }
        double getScore() { return score; }
    }

    private static class Outcome {
        private boolean changed;
        private String status;
        private Long entryObservationId;
        private Long entrySourceTimeEpochMs;
        private String entrySourceDate;
        private Double entryPrice;
        private String t1TradeDate;
        private Double t1Open;
        private Double t1High;
        private Double t1Low;
        private Double t1Close;
        private Double maxDrawdownPct;
        private Double closeReturnPct;
        private Double gapPct;
        private Double maxUpsidePct;
        private Boolean drawdown5Hit;
        private String dataQualityReason;
        private Date maturedAt;
    }
}
