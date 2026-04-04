package com.aimeeting.room.controller;

import com.aimeeting.room.common.ApiResponse;
import com.aimeeting.room.config.AiModelProperties;
import com.aimeeting.room.config.ModelEntry;
import com.aimeeting.room.config.ModelRegistryStore;
import com.aimeeting.room.dto.ModelConfigRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private final AiModelProperties aiModelProperties;
    private final ModelRegistryStore registry;

    // ============================================================
    // 模型注册表 CRUD
    // ============================================================

    /** 获取所有已配置模型（API Key 脱敏） */
    @GetMapping("/registry")
    public ApiResponse<List<Map<String, Object>>> listRegistry() {
        List<Map<String, Object>> result = registry.getAll().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id",          e.getId());
            m.put("displayName", e.getDisplayName());
            m.put("modelId",     e.getModelId());
            m.put("apiUrl",      e.getApiUrl());
            m.put("apiKey",      ModelRegistryStore.maskKey(e.getApiKey()));
            m.put("apiFormat",   e.getApiFormat());
            m.put("enabled",     e.isEnabled());
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.ok(result);
    }

    /** 新增模型 */
    @PostMapping("/registry")
    public ApiResponse<ModelEntry> addModel(@RequestBody ModelEntry entry) {
        if (entry.getDisplayName() == null || entry.getDisplayName().isBlank())
            return ApiResponse.fail("displayName 不能为空");
        if (entry.getModelId() == null || entry.getModelId().isBlank())
            return ApiResponse.fail("modelId 不能为空");
        if (entry.getApiUrl() == null || entry.getApiUrl().isBlank())
            return ApiResponse.fail("apiUrl 不能为空");
        if (entry.getApiFormat() == null) entry.setApiFormat("openai");

        ModelEntry saved = registry.add(entry);
        log.info("Model added: {} ({})", saved.getDisplayName(), saved.getModelId());
        return ApiResponse.ok(saved);
    }

    /** 更新模型（apiKey 含 *** 则不修改） */
    @PutMapping("/registry/{id}")
    public ApiResponse<Void> updateModel(@PathVariable String id, @RequestBody ModelEntry entry) {
        ModelEntry existing = registry.getById(id);
        if (existing == null) return ApiResponse.fail(404, "模型不存在");

        // 未传入真实 key 时保留原 key
        if (entry.getApiKey() == null || entry.getApiKey().contains("***")) {
            entry.setApiKey(existing.getApiKey());
        }
        registry.update(id, entry);
        log.info("Model updated: {}", id);
        return ApiResponse.ok(null);
    }

    /** 删除模型 */
    @DeleteMapping("/registry/{id}")
    public ApiResponse<Void> deleteModel(@PathVariable String id) {
        if (!registry.delete(id)) return ApiResponse.fail(404, "模型不存在");
        log.info("Model deleted: {}", id);
        return ApiResponse.ok(null);
    }

    /** 供创建会议时使用：返回所有已启用的模型列表 */
    @GetMapping("/available-models")
    public ApiResponse<List<Map<String, Object>>> availableModels() {
        List<Map<String, Object>> list = registry.getEnabled().stream().map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("modelKey",    e.getId());
            m.put("modelId",     e.getModelId());
            m.put("displayName", e.getDisplayName());
            m.putAll(buildVerificationMeta(e.getId()));
            return m;
        }).collect(Collectors.toList());
        return ApiResponse.ok(list);
    }

    private Map<String, Object> buildVerificationMeta(String modelKey) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if ("b6465a2d".equals(modelKey)) {
            meta.put("verificationStatus", "verified_stable");
            meta.put("verificationLabel", "已验证稳定");
            meta.put("verificationNote", "已完成本地会议主链路回归，适合作为稳定会议模型。");
            meta.put("recommended", true);
            return meta;
        }
        if ("9fb32460".equals(modelKey)) {
            meta.put("verificationStatus", "verified_search_experimental");
            meta.put("verificationLabel", "已验证搜索实验");
            meta.put("verificationNote", "已验证可用，但会先尝试实验性内置搜索，超时后再降级到稳定链路。");
            meta.put("recommended", true);
            return meta;
        }
        meta.put("verificationStatus", "unverified");
        meta.put("verificationLabel", "未验证");
        meta.put("verificationNote", "当前本地未完成完整会议回归，可能出现超时、兜底发言或总结缺失。");
        meta.put("recommended", false);
        return meta;
    }

    // ============================================================
    // 会议引擎参数（保持不变）
    // ============================================================

    @GetMapping("/meeting")
    public ApiResponse<Map<String, Object>> getMeetingConfig() {
        AiModelProperties.MeetingConfig m = aiModelProperties.getMeeting();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("consensusThreshold",   m.getConsensusThreshold());
        result.put("minAgreementCount",    m.getMinAgreementCount());
        result.put("maxRoundsDefault",     m.getMaxRoundsDefault());
        result.put("baseReward",           m.getBaseReward());
        result.put("basePenalty",          m.getBasePenalty());
        result.put("dissentPenaltyFactor", m.getDissentPenaltyFactor());
        return ApiResponse.ok(result);
    }

    @PostMapping("/meeting")
    public ApiResponse<Void> saveMeetingConfig(@RequestBody ModelConfigRequest.MeetingParams mp) {
        AiModelProperties.MeetingConfig mc = aiModelProperties.getMeeting();
        if (mp.getConsensusThreshold()   != null) mc.setConsensusThreshold(mp.getConsensusThreshold());
        if (mp.getMinAgreementCount()    != null) mc.setMinAgreementCount(mp.getMinAgreementCount());
        if (mp.getMaxRoundsDefault()     != null) mc.setMaxRoundsDefault(mp.getMaxRoundsDefault());
        if (mp.getBaseReward()           != null) mc.setBaseReward(mp.getBaseReward());
        if (mp.getBasePenalty()          != null) mc.setBasePenalty(mp.getBasePenalty());
        if (mp.getDissentPenaltyFactor() != null) mc.setDissentPenaltyFactor(mp.getDissentPenaltyFactor());
        log.info("Meeting config updated");
        return ApiResponse.ok(null);
    }
}
