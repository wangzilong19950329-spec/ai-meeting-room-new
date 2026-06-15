package com.aimeeting.room.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;

@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureSchema() {
        ensureColumn("ai_workflow_stage_template", "input_stage_keys",
                "ALTER TABLE ai_workflow_stage_template ADD COLUMN input_stage_keys TEXT NULL COMMENT 'ALL_READY输入阶段标识JSON数组' AFTER search_strategy");
    }

    private void ensureColumn(String tableName, String columnName, String alterSql) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Integer.class,
                    tableName,
                    columnName
            );
            if (count != null && count > 0) {
                return;
            }
            jdbcTemplate.execute(alterSql);
        } catch (Exception e) {
            log.warn("failed to ensure workflow column {}.{}: {}", tableName, columnName, e.getMessage());
        }
    }
}
