package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.AnalysisReuseOnlyMissException;
import com.aimeeting.room.service.StockWatchTechnicalAnalysisService;
import com.aimeeting.room.service.StockWatchPredictionService;
import com.aimeeting.room.service.StockSymbol;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-watch/technical-analysis")
@RequiredArgsConstructor
public class StockWatchTechnicalAnalysisController {

    private final StockWatchTechnicalAnalysisService technicalAnalysisService;
    private final StockWatchPredictionService predictionService;

    @GetMapping
    public ApiResponse<Map<String, Object>> history(@RequestParam("symbol") String symbol,
                                                    @RequestParam(value = "market", required = false) String market,
                                                    @RequestParam(value = "date", required = false)
                                                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                    @RequestParam(value = "days", defaultValue = "30") int days,
                                                    @RequestParam(value = "periodType", defaultValue = "DAY") String periodType,
                                                    @RequestParam(value = "periodKey", required = false) String periodKey) {
        return ApiResponse.ok(technicalAnalysisService.history(symbol, market, date, days, periodType, periodKey));
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(technicalAnalysisService.status());
    }

    @GetMapping("/predictions")
    public ApiResponse<Map<String, Object>> predictions(@RequestParam("symbol") String symbol,
                                                        @RequestParam(value = "market", required = false) String market,
                                                        @RequestParam(value = "limit", defaultValue = "200") int limit) {
        StockSymbol normalized = StockSymbol.parse(symbol, market);
        return ApiResponse.ok(predictionService.history(normalized.getSymbol(), normalized.getMarket(), limit));
    }

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.ok(technicalAnalysisService.runManual(
                    body == null ? Collections.emptyMap() : body
            ));
        } catch (AnalysisReuseOnlyMissException miss) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorCode", "ANALYSIS_REUSE_ONLY_MISS");
            data.put("observationKey", miss.getObservationKey());
            data.put("retryable", false);
            ApiResponse<Map<String, Object>> response = ApiResponse.fail(409, "当前观察窗口没有可复用分析");
            response.setData(data);
            return response;
        }
    }
}
