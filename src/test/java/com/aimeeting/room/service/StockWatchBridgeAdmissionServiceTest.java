package com.aimeeting.room.service;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockWatchBridgeAdmissionServiceTest {

    @Test
    void doesNotSerializeRequestsBeforeTheAccountBroker() throws Exception {
        StockWatchBridgeAdmissionService service = new StockWatchBridgeAdmissionService();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        try {
            Future<String> first = executor.submit(() -> service.execute(100, "first", () -> {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
                active.decrementAndGet();
                return "first";
            }));
            Future<String> second = executor.submit(() -> service.execute(200, "second", () -> {
                int current = active.incrementAndGet();
                maxActive.accumulateAndGet(current, Math::max);
                entered.countDown();
                release.await(2, TimeUnit.SECONDS);
                active.decrementAndGet();
                return "second";
            }));

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals("second", second.get(2, TimeUnit.SECONDS));
            assertEquals(2, maxActive.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }
}
