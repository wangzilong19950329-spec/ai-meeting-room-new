package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.StockWatchTechnicalAnalysisService;
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
import java.util.Map;

@RestController
@RequestMapping("/api/stock-watch/technical-analysis")
@RequiredArgsConstructor
public class StockWatchTechnicalAnalysisController {

    private final StockWatchTechnicalAnalysisService technicalAnalysisService;

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

    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(technicalAnalysisService.runManual(
                body == null ? Collections.emptyMap() : body
        ));
    }
}
