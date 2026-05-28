package com.aimeeting.room;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.aimeeting.room.dao")
@EnableScheduling
public class AiMeetingApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiMeetingApplication.class, args);
    }
}
