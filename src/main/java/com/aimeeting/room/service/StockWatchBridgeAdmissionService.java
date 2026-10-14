package com.aimeeting.room.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.concurrent.Callable;

@Service
@Slf4j
public class StockWatchBridgeAdmissionService {

    public static final int PRIORITY_ALERT_TARGETED_TECHNICAL = 400;
    public static final int PRIORITY_GENERAL_LEGACY_RULE = 300;
    public static final int PRIORITY_MANUAL_TECHNICAL = 200;
    public static final int PRIORITY_SCHEDULED_TECHNICAL = 100;

    public <T> T execute(int priority, String label, Callable<T> action) throws Exception {
        String effectiveLabel = label == null ? "stock-watch-bridge" : label;
        long startedAt = System.currentTimeMillis();
        log.info("stock-watch bridge submit label={} priority={}", effectiveLabel, priority);
        try {
            return action.call();
        } finally {
            log.info("stock-watch bridge completed label={} priority={} elapsedMs={}",
                    effectiveLabel, priority, System.currentTimeMillis() - startedAt);
        }
    }
}
