package com.aimeeting.room.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    @Autowired
    private AiModelProperties aiModelProperties;

    @Bean(name = "meetingExecutor")
    public ExecutorService meetingExecutor() {
        return Executors.newFixedThreadPool(
                aiModelProperties.getMeeting().getAsyncThreadPoolSize()
        );
    }

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
