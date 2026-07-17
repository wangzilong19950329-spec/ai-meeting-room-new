package com.aimeeting.room;

import com.alibaba.fastjson.JSON;
import com.aimeeting.room.service.StockWatchV6MigrationService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.Locale;

public final class StockWatchV6MigrationApplication {

    private StockWatchV6MigrationApplication() {
    }

    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(AiMeetingApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
        try {
            Environment environment = context.getEnvironment();
            String action = environment.getProperty("stock-watch.technical-analysis.v6-migration-action", "preview")
                    .trim().toLowerCase(Locale.ROOT);
            StockWatchV6MigrationService service = context.getBean(StockWatchV6MigrationService.class);
            if ("schema".equals(action)) {
                service.ensureSchema();
                System.out.println("stock-watch-v6 schema ready");
            } else if ("preview".equals(action)) {
                System.out.println(JSON.toJSONString(service.preview().toPayload()));
            } else if ("migrate".equals(action)) {
                System.out.println(JSON.toJSONString(service.migrate().toPayload()));
            } else {
                throw new IllegalArgumentException("stock_watch_v6_migration_action_invalid:" + action);
            }
        } finally {
            context.close();
        }
    }
}
