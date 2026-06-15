package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
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

    @GetMapping("/quotes")
    public ApiResponse<List<Map<String, Object>>> getQuotes(@RequestParam("symbols") String symbols) {
        return ApiResponse.ok(stockQuoteService.fetchQuotes(symbols));
    }
}
