package com.aimeeting.room;

import com.aimeeting.room.dao.StockWatchPredictionMapper;
import com.aimeeting.room.dao.StockWatchTechnicalAnalysisMapper;
import com.aimeeting.room.entity.StockWatchPrediction;
import com.aimeeting.room.entity.StockWatchTechnicalAnalysis;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
@Transactional
class StockWatchV6ReaderVisibilitySmokeTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StockWatchTechnicalAnalysisMapper analysisMapper;
    @Autowired
    private StockWatchPredictionMapper predictionMapper;

    @Test
    void activeAndArchiveReadersUseReadyCutoffAndCanonicalScope() {
        jdbc.update("MERGE INTO stock_watch_analysis_migration_state KEY(migration_key) "
                + "VALUES('stock-watch-v6',6,'READY',990000,990001,1,'test',CURRENT_TIMESTAMP)");
        jdbc.update("INSERT INTO stock_watch_analysis_task(id,submission_key,request_digest,purpose,symbol,market,"
                        + "period_type,observation_key,generation,status,request_envelope_json,row_version) "
                        + "VALUES(990001,'reader-runtime','digest','stock-analysis-response-v6','009996','sz',"
                        + "'DAY','DAY:2026-07-16:10:00',1,'PROJECTED_SUCCESS','{}',0)");
        jdbc.update("INSERT INTO stock_watch_analysis_observation_identity(id,identity_key,analysis_scope,symbol,market,"
                        + "period_type,observation_key,canonical_task_id,migration_state,row_version) "
                        + "VALUES(990001,'reader-identity','TECHNICAL_RUNTIME','009996','sz','DAY',"
                        + "'DAY:2026-07-16:10:00',990001,'CANONICAL',0)");
        insertAnalysis(990001, null, "09:30");
        insertAnalysis(990002, null, "09:45");
        insertAnalysis(990003, 990001L, "10:00");
        insertPrediction(990001, 991001);
        insertPrediction(990002, 991002);
        insertPrediction(990003, 991003);

        assertNull(analysisMapper.selectLatestBySymbolDateSlot("009996", "sz",
                Date.valueOf("2026-07-16"), "09:30", "DAY", "2026-07-16"));
        StockWatchTechnicalAnalysis canonical = analysisMapper.selectLatestBySymbolDateSlot("009996", "sz",
                Date.valueOf("2026-07-16"), "10:00", "DAY", "2026-07-16");
        assertEquals(990003L, canonical.getId());
        assertEquals(6, canonical.getResponseContractVersion());
        List<Long> visibleAnalysis = analysisMapper.selectBySymbolDateRange("009996", "sz",
                        Date.valueOf("2026-07-16"), Date.valueOf("2026-07-16"), "DAY")
                .stream().map(StockWatchTechnicalAnalysis::getId).collect(Collectors.toList());
        assertEquals(java.util.Arrays.asList(990003L, 990001L), visibleAnalysis);
        List<Long> visiblePredictions = predictionMapper.selectRecent("009996", "sz", 10)
                .stream().map(StockWatchPrediction::getId).collect(Collectors.toList());
        assertEquals(java.util.Arrays.asList(991003L, 991001L), visiblePredictions);
    }

    private void insertAnalysis(long id, Long taskId, String slot) {
        jdbc.update("INSERT INTO stock_watch_technical_analysis(id,analysis_task_id,symbol,market,stock_name,"
                        + "analysis_date,run_slot,period_type,period_key,status,title,gmt_create) "
                        + "VALUES(?,?,'009996','sz','可见性测试',? ,?,'DAY','2026-07-16','SUCCESS','可见性测试',CURRENT_TIMESTAMP)",
                id, taskId, Date.valueOf(LocalDate.of(2026, 7, 16)), slot);
    }

    private void insertPrediction(long analysisId, long predictionId) {
        jdbc.update("INSERT INTO stock_watch_prediction(id,analysis_id,symbol,market,horizon_code,horizon_trading_days,"
                        + "forecast_date,source_price,direction,status) VALUES(?,?,'009996','sz','T1',1,?,100,'UP','PENDING')",
                predictionId, analysisId, Date.valueOf("2026-07-16"));
    }
}
