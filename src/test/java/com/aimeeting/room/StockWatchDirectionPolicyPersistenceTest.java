package com.aimeeting.room;

import com.aimeeting.room.dao.StockWatchPredictionMapper;
import com.aimeeting.room.entity.StockWatchPrediction;
import com.aimeeting.room.service.StockWatchPredictionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = "spring.main.web-application-type=none")
@ActiveProfiles("smoketest")
@Transactional
class StockWatchDirectionPolicyPersistenceTest {

    @Autowired StockWatchPredictionMapper mapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void newForecastStoresPolicyAndLegacyRowsReceiveLegacyDefault() {
        StockWatchPrediction prediction = new StockWatchPrediction();
        prediction.setAnalysisId(987654321L);
        prediction.setSymbol("300750");
        prediction.setMarket("sz");
        prediction.setHorizonCode("T5");
        prediction.setHorizonTradingDays(5);
        prediction.setForecastDate(Date.valueOf("2026-08-07"));
        prediction.setSourcePrice(388.31);
        prediction.setDirection("SIDEWAYS");
        prediction.setDirectionPolicyVersion(StockWatchPredictionService.CURRENT_DIRECTION_POLICY);
        prediction.setTargetLow(375.0);
        prediction.setTargetHigh(410.0);
        prediction.setStatus("PENDING");

        mapper.insert(prediction);

        assertEquals(StockWatchPredictionService.CURRENT_DIRECTION_POLICY,
                mapper.selectByAnalysisId(987654321L).get(0).getDirectionPolicyVersion());

        jdbcTemplate.update("INSERT INTO stock_watch_prediction "
                        + "(analysis_id,symbol,market,horizon_code,horizon_trading_days,forecast_date,"
                        + "source_price,direction,status) VALUES (?,?,?,?,?,?,?,?,?)",
                987654322L, "300750", "sz", "T1", 1, Date.valueOf("2026-08-07"),
                388.31, "UP", "PENDING");

        assertEquals(StockWatchPredictionService.LEGACY_DIRECTION_POLICY,
                mapper.selectByAnalysisId(987654322L).get(0).getDirectionPolicyVersion());
    }
}
