package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.StockWatchMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-watch/monitor")
@RequiredArgsConstructor
public class StockWatchMonitorController {

    private final StockWatchMonitorService stockWatchMonitorService;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(stockWatchMonitorService.status());
    }

    @PostMapping("/run-once")
    public ApiResponse<Map<String, Object>> runOnce(@RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(stockWatchMonitorService.runOnce(
                body == null ? Collections.emptyMap() : body,
                "manual_api"
        ));
    }
}
