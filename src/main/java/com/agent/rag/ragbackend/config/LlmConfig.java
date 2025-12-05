package com.agent.rag.ragbackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 大语言模型（LLM）配置类。
 *
 * <p>该类负责从配置文件（如 application.yml）中加载 LLM 相关的配置，
 * 并作为判断模型能力的“唯一真实数据源”。</p>
 */
@Component
@ConfigurationProperties(prefix = "rag.llm")
@Data // 使用 Lombok 自动生成 Getter/Setter/toString 等方法
public class LlmConfig {

    /**
     * 默认模型ID。
     */
    private String defaultModel;

    /**
     * 模型定义列表（对应 YAML 中的 models 数组）。
     */
    private List<ModelDefinition> models;

    /**
     * 检查指定ID的模型是否支持深度思考能力。
     *
     * <p>逻辑变更：不再查询 Model 枚举，而是直接遍历配置文件中定义的 models 列表。
     * 只要 YAML 中该模型的 features 包含 "deep_thinking"，即认为支持。</p>
     *
     * @param modelId 模型ID，如 "qwen-plus"。可为 null。
     * @return 如果模型在配置中存在且 features 包含 "deep_thinking"，返回 true。
     */
    public boolean supportsDeepThinking(String modelId) {
        if (modelId == null || models == null) {
            return false;
        }

        // 1. 在配置列表中查找匹配 ID 的模型 (忽略大小写)
        Optional<ModelDefinition> targetModel = models.stream()
                .filter(m -> m.getId().equalsIgnoreCase(modelId))
                .findFirst();

        // 2. 检查找到的模型，其 features 列表是否包含 "deep_thinking"
        // 注意：这里匹配的是 YAML 中 features 里的字符串
        return targetModel
                .map(model -> model.getFeatures() != null && model.getFeatures().contains("deep_thinking"))
                .orElse(false);
    }

    /**
     * 获取默认模型ID。
     *
     * <p>如果配置文件中未设置，则返回一个安全的默认值。</p>
     *
     * @return 默认模型ID。
     */
    public String getDefaultModelId() {
        return Objects.requireNonNullElse(defaultModel, "qwen-plus");
    }

    /**
     * 模型定义内部类，用于映射配置文件中的单个模型项。
     * 使用 @Data 自动生成 Getter/Setter。
     */
    @Data
    public static class ModelDefinition {
        private String id;
        private String name;
        private String provider;
        /**
         * 功能特性列表，对应 YAML 中的 features: [...]
         * 例如: ["streaming", "deep_thinking"]
         */
        private List<String> features;
    }
}