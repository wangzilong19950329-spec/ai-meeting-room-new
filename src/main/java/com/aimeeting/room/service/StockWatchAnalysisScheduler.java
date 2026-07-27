package com.aimeeting.room.service;

import com.aimeeting.room.entity.StockWatchItem;
import com.aimeeting.room.service.StockWatchAnalysisDemandService.DemandCreationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockWatchAnalysisScheduler {

    private final StockWatchAnalysisSchedulerOwnershipService ownershipService;
    private final StockWatchAnalysisPolicyService policyService;
    private final StockWatchAnalysisSchedulePlanner planner;
    private final StockWatchAnalysisDemandService demandService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(
            fixedDelayString = "${stock-watch.analysis-scheduler.scan-interval-ms:60000}",
            initialDelayString = "${stock-watch.analysis-scheduler.initial-delay-ms:90000}"
    )
    public void scheduledReconcile() {
        if (!ownershipService.isPolicyOwner() || !running.compareAndSet(false, true)) return;
        try {
            reconcileNow();
        } catch (Exception e) {
            ownershipService.recordError(diagnostic(e));
            log.warn("stock-watch policy scheduler failed error={}", diagnostic(e));
        } finally {
            running.set(false);
        }
    }

    public Map<String, Object> reconcileNow() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> ownership = ownershipService.snapshot();
        result.put("ownership", ownership);
        if (!"POLICY".equals(ownership.get("mode"))) {
            result.put("created", 0);
            result.put("reason", "OWNERSHIP_NOT_POLICY");
            return result;
        }
        int created = 0;
        int reused = 0;
        int failed = 0;
        ZonedDateTime now = ZonedDateTime.now(StockWatchAnalysisSchedulePlanner.CHINA_ZONE);
        List<StockWatchItem> items = policyService.enabledItems();
        for (StockWatchItem item : items) {
            demandService.supersedePolicyMismatch(item);
            for (StockWatchAnalysisSchedulePlanner.DueDecision decision : planner.dueDecisions(item, now)) {
                try {
                    DemandCreationResult creation = demandService.createScheduled(item, decision);
                    if (creation.isCreated()) {
                        created++;
                    } else if (creation.isReused()) {
                        reused++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("stock-watch policy slot create failed symbol={}.{} slot={} error={}",
                            item.getSymbol(), item.getMarket(), decision.getScheduleSlotKey(), diagnostic(e));
                }
            }
        }
        result.put("created", created);
        result.put("reused", reused);
        result.put("failed", failed);
        result.put("evaluatedStocks", items.size());
        result.put("at", now.toString());
        return result;
    }

    private String diagnostic(Exception e) {
        return e == null || e.getMessage() == null ? "unknown_error" : e.getMessage();
    }
}
