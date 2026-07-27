package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.StockWatchRuntimeStatusService;
import com.aimeeting.room.service.StockQuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-watch")
@RequiredArgsConstructor
public class StockWatchController {

    private final StockQuoteService stockQuoteService;
    private final StockWatchRuntimeStatusService runtimeStatusService;

    @GetMapping("/quotes")
    public ApiResponse<List<Map<String, Object>>> getQuotes(@RequestParam("symbols") String symbols) {
        return ApiResponse.ok(stockQuoteService.fetchQuotes(symbols));
    }

    @GetMapping("/intraday-bars")
    public ApiResponse<Map<String, Object>> getIntradayBars(@RequestParam("symbol") String symbol,
                                                            @RequestParam(value = "market", required = false) String market) {
        return ApiResponse.ok(stockQuoteService.fetchIntradayBars(symbol, market));
    }

    @GetMapping("/runtime-status")
    public ApiResponse<Map<String, Object>> runtimeStatus(@RequestParam(value = "symbol", required = false) String symbol,
                                                         @RequestParam(value = "market", required = false) String market) {
        return ApiResponse.ok(runtimeStatusService.current(symbol, market));
    }
}
