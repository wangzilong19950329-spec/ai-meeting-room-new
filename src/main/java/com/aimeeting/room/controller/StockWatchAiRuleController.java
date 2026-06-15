package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.service.StockWatchAiRuleService;
import com.aimeeting.room.service.StockWatchAiRuleService.AiWatchRule;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-watch/ai-rules")
@RequiredArgsConstructor
public class StockWatchAiRuleController {

    private final StockWatchAiRuleService aiRuleService;

    @GetMapping
    public ApiResponse<List<AiWatchRule>> list() {
        return ApiResponse.ok(aiRuleService.listAll());
    }

    @PostMapping
    public ApiResponse<AiWatchRule> create(@RequestBody Map<String, Object> payload) {
        try {
            return ApiResponse.ok(aiRuleService.create(payload));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<AiWatchRule> update(@PathVariable String id, @RequestBody Map<String, Object> payload) {
        try {
            AiWatchRule updated = aiRuleService.update(id, payload);
            return updated == null ? ApiResponse.fail("AI观察条件不存在") : ApiResponse.ok(updated);
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        return aiRuleService.delete(id) ? ApiResponse.ok() : ApiResponse.fail("AI观察条件不存在");
    }
}
