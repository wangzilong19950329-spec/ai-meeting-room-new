package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.StockWatchDeliveryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-watch/delivery-config")
@RequiredArgsConstructor
public class StockWatchDeliveryController {

    private final StockWatchDeliveryService deliveryService;

    @GetMapping
    public ApiResponse<Map<String, Object>> getConfig(@RequestParam("symbol") String symbol,
                                                      @RequestParam(value = "market", required = false) String market) {
        return ApiResponse.ok(deliveryService.getConfig(symbol, market));
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> saveConfig(@RequestBody Map<String, Object> body) {
        try {
            return ApiResponse.ok(deliveryService.saveConfig(body == null ? Collections.emptyMap() : body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @GetMapping("/preview")
    public ApiResponse<Map<String, Object>> preview(@RequestParam("symbol") String symbol,
                                                    @RequestParam(value = "market", required = false) String market) {
        return ApiResponse.ok(deliveryService.preview(symbol, market));
    }

    @PostMapping("/dispatch-now")
    public ApiResponse<Map<String, Object>> dispatchNow(@RequestBody Map<String, Object> body) {
        try {
            String symbol = body == null ? "" : String.valueOf(body.getOrDefault("symbol", ""));
            String market = body == null ? "" : String.valueOf(body.getOrDefault("market", ""));
            return ApiResponse.ok(deliveryService.dispatchNow(symbol, market));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping("/test-email")
    public ApiResponse<Map<String, Object>> testEmail(@RequestBody Map<String, Object> body) {
        try {
            String symbol = body == null ? "" : String.valueOf(body.getOrDefault("symbol", ""));
            String market = body == null ? "" : String.valueOf(body.getOrDefault("market", ""));
            return ApiResponse.ok(deliveryService.testEmail(symbol, market, body));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }
}
