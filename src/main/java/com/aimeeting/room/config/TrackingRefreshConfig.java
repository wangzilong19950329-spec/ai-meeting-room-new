package com.aimeeting.room.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class TrackingRefreshConfig {

    @Bean(name = "trackingRefreshExecutor", destroyMethod = "shutdown")
    public ExecutorService trackingRefreshExecutor() {
        AtomicInteger seq = new AtomicInteger(1);
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("tracking-refresh-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }
}
