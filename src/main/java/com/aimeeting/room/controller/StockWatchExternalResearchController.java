package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.StockWatchExternalResearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/stock-watch/external-research")
@RequiredArgsConstructor
public class StockWatchExternalResearchController {

    private final StockWatchExternalResearchService externalResearchService;

    @GetMapping("/evidence")
    public ApiResponse<Map<String, Object>> evidence(@RequestParam("symbol") String symbol,
                                                     @RequestParam(value = "market", required = false) String market) {
        return ApiResponse.ok(externalResearchService.fetchEvidence(symbol, market));
    }
}
