package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.StockWatchFcCardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-watch/fc-cards")
@RequiredArgsConstructor
public class StockWatchFcCardController {

    private final StockWatchFcCardService fcCardService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam("symbol") String symbol,
                                                 @RequestParam(value = "market", required = false) String market) {
        try {
            return ApiResponse.ok(fcCardService.list(symbol, market));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.ok(fcCardService.save(body == null ? Collections.emptyMap() : body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable("id") Long id,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.ok(fcCardService.update(id, body == null ? Collections.emptyMap() : body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable("id") Long id) {
        fcCardService.delete(id);
        return ApiResponse.ok();
    }

    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importMarkdown(@RequestBody(required = false) Map<String, Object> body) {
        try {
            return ApiResponse.ok(fcCardService.importMarkdown(body == null ? Collections.emptyMap() : body));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(400, e.getMessage());
        }
    }
}
