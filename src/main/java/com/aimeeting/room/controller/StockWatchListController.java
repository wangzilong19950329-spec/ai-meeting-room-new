package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.StockWatchListService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-watch/watchlist")
@RequiredArgsConstructor
public class StockWatchListController {

    private final StockWatchListService watchListService;

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(watchListService.listEnabled());
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.ok(watchListService.save(body == null ? Collections.emptyMap() : body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }
}
