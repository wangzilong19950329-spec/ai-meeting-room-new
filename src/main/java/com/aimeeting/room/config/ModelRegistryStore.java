package com.aimeeting.room.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 模型注册表：管理所有可用的 AI 模型配置。
 * 启动时从 application.yml 预填充内置模型，用户可在运行时增删改。
 * 重启后恢复 yml 默认值（生产环境可接入数据库持久化）。
 */
@Component
public class ModelRegistryStore {

    private final List<ModelEntry> entries = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private AiModelProperties props;

    @Value("${ai.registry.store-file:${user.home}/.aimeeting-room/model-registry.json}")
    private String storeFile;

    @PostConstruct
    public void init() {
        if (loadFromDisk()) {
            return;
        }
        loadBuiltinDefaults();
        saveToDisk();
    }

    private void loadBuiltinDefaults() {
        AiModelProperties.ModelConfig openai = props.getModels().get("openai");
        if (openai != null) {
            addBuiltin("GPT-4o",         "gpt-4o",           openai.getApiUrl(), openai.getApiKey(), "openai", openai.isEnabled());
            addBuiltin("GPT-4 Turbo",    "gpt-4-turbo",      openai.getApiUrl(), openai.getApiKey(), "openai", openai.isEnabled());
            addBuiltin("GPT-3.5 Turbo",  "gpt-3.5-turbo",    openai.getApiUrl(), openai.getApiKey(), "openai", openai.isEnabled());
        }

        AiModelProperties.ModelConfig claude = props.getModels().get("claude");
        if (claude != null) {
            addBuiltin("Claude 3.5 Sonnet", "claude-3-5-sonnet-20241022", claude.getApiUrl(), claude.getApiKey(), "claude", claude.isEnabled());
            addBuiltin("Claude 3 Opus",     "claude-3-opus-20240229",     claude.getApiUrl(), claude.getApiKey(), "claude", claude.isEnabled());
        }

        AiModelProperties.ModelConfig tongyi = props.getModels().get("tongyi");
        if (tongyi != null) {
            addBuiltin("通义千问 Max",  "qwen-max",   tongyi.getApiUrl(), tongyi.getApiKey(), "openai", tongyi.isEnabled());
            addBuiltin("通义千问 Plus", "qwen-plus",  tongyi.getApiUrl(), tongyi.getApiKey(), "openai", tongyi.isEnabled());
            addBuiltin("通义千问 Turbo","qwen-turbo", tongyi.getApiUrl(), tongyi.getApiKey(), "openai", tongyi.isEnabled());
        }

        AiModelProperties.ModelConfig deepseek = props.getModels().get("deepseek");
        if (deepseek != null) {
            addBuiltin("DeepSeek R1", "deepseek-reasoner", deepseek.getApiUrl(), deepseek.getApiKey(), "openai", deepseek.isEnabled());
            addBuiltin("DeepSeek V3", "deepseek-chat",     deepseek.getApiUrl(), deepseek.getApiKey(), "openai", deepseek.isEnabled());
        }
    }

    private boolean loadFromDisk() {
        Path path = storePath();
        if (!Files.exists(path)) {
            return false;
        }
        try {
            List<ModelEntry> persisted = objectMapper.readValue(
                    Files.readAllBytes(path),
                    new TypeReference<List<ModelEntry>>() {}
            );
            entries.clear();
            if (persisted != null) {
                entries.addAll(persisted);
            }
            return !entries.isEmpty();
        } catch (IOException e) {
            entries.clear();
            return false;
        }
    }

    private synchronized void saveToDisk() {
        Path path = storePath();
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), entries);
        } catch (IOException e) {
            throw new IllegalStateException("保存模型注册表失败: " + path, e);
        }
    }

    private Path storePath() {
        return Paths.get(storeFile).toAbsolutePath().normalize();
    }

    private void addBuiltin(String displayName, String modelId, String apiUrl, String apiKey,
                             String format, boolean enabled) {
        ModelEntry e = new ModelEntry();
        e.setId(shortId());
        e.setDisplayName(displayName);
        e.setModelId(modelId);
        e.setApiUrl(apiUrl != null ? apiUrl : "");
        e.setApiKey(apiKey != null ? apiKey : "");
        e.setApiFormat(format);
        e.setEnabled(enabled);
        entries.add(e);
    }

    // ---- CRUD ----

    public List<ModelEntry> getAll() {
        return Collections.unmodifiableList(entries);
    }

    public List<ModelEntry> getEnabled() {
        return entries.stream().filter(ModelEntry::isEnabled).collect(Collectors.toList());
    }

    public ModelEntry getById(String id) {
        return entries.stream().filter(e -> e.getId().equals(id)).findFirst().orElse(null);
    }

    public ModelEntry getByModelId(String selector) {
        if (selector == null || selector.isBlank()) return null;
        ModelEntry byId = entries.stream()
                .filter(e -> e.getId().equals(selector))
                .findFirst()
                .orElse(null);
        if (byId != null) {
            return byId;
        }
        return entries.stream()
                .filter(e -> e.getModelId().equals(selector))
                .findFirst()
                .orElse(null);
    }

    public ModelEntry add(ModelEntry entry) {
        entry.setId(shortId());
        entries.add(entry);
        saveToDisk();
        return entry;
    }

    public boolean update(String id, ModelEntry updated) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getId().equals(id)) {
                updated.setId(id);
                entries.set(i, updated);
                saveToDisk();
                return true;
            }
        }
        return false;
    }

    public boolean delete(String id) {
        boolean removed = entries.removeIf(e -> e.getId().equals(id));
        if (removed) {
            saveToDisk();
        }
        return removed;
    }

    // ---- helpers ----

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "***";
        return key.substring(0, 4) + "***" + key.substring(key.length() - 4);
    }
}
