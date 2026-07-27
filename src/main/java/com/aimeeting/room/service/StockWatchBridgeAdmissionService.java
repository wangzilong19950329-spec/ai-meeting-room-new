package com.aimeeting.room.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class StockWatchBridgeAdmissionService {

    public static final int PRIORITY_ALERT_TARGETED_TECHNICAL = 400;
    public static final int PRIORITY_GENERAL_LEGACY_RULE = 300;
    public static final int PRIORITY_MANUAL_TECHNICAL = 200;
    public static final int PRIORITY_SCHEDULED_TECHNICAL = 100;

    private final PriorityBlockingQueue<AdmissionTask<?>> queue = new PriorityBlockingQueue<>();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread worker;

    public StockWatchBridgeAdmissionService() {
        worker = new Thread(this::runLoop, "stock-watch-bridge-admission");
        worker.setDaemon(true);
        worker.start();
    }

    public <T> T execute(int priority, String label, Callable<T> action) throws Exception {
        AdmissionTask<T> task = new AdmissionTask<>(
                priority,
                sequence.incrementAndGet(),
                label == null ? "stock-watch-bridge" : label,
                action
        );
        queue.put(task);
        try {
            return task.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new IllegalStateException(cause);
        }
    }

    private void runLoop() {
        while (running.get() || !queue.isEmpty()) {
            try {
                AdmissionTask<?> task = queue.take();
                long waitedMs = Math.max(0L, System.currentTimeMillis() - task.enqueuedAt);
                log.info("stock-watch bridge admission start label={} priority={} waitedMs={} queued={}",
                        task.label, task.priority, waitedMs, queue.size());
                long startedAt = System.currentTimeMillis();
                task.run();
                log.info("stock-watch bridge admission end label={} priority={} elapsedMs={} queued={}",
                        task.label, task.priority, System.currentTimeMillis() - startedAt, queue.size());
            } catch (InterruptedException interrupted) {
                if (running.get()) {
                    log.warn("stock-watch bridge admission worker interrupted");
                }
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        worker.interrupt();
    }

    private static final class AdmissionTask<T> extends FutureTask<T>
            implements Comparable<AdmissionTask<?>> {
        private final int priority;
        private final long sequence;
        private final String label;
        private final long enqueuedAt = System.currentTimeMillis();

        private AdmissionTask(int priority, long sequence, String label, Callable<T> action) {
            super(action);
            this.priority = priority;
            this.sequence = sequence;
            this.label = label;
        }

        @Override
        public int compareTo(AdmissionTask<?> other) {
            int byPriority = Integer.compare(other.priority, priority);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }
}
