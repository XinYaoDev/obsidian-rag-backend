package com.agent.rag.ragbackend.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL) // 关键：如果字段是 null，生成的 JSON 里就完全不显示
public class OpenAiRequest {

    private String model;

    private List<Message> messages;

    private Boolean stream;

    // ==========================================
    // 🔥 新增：深度思考相关参数 (直接放在根节点)
    // ==========================================

    /**
     * 阿里云 Qwen (QwQ) 专用参数
     * JSON 键名: enable_thinking
     */
    @JsonProperty("enable_thinking")
    private Boolean enableThinking;

    /**
     * 阿里云 Qwen 可选参数 (控制思考预算: Low/Medium/High)
     * JSON 键名: thinking_effort
     */
    @JsonProperty("thinking_effort")
    private String thinkingEffort;

    /**
     * DeepSeek R1 专用参数 (控制思考强度: high)
     * JSON 键名: reasoning_effort
     */
    @JsonProperty("reasoning_effort")
    private String reasoningEffort;

    // ==========================================
    // 常规参数 (根据需要添加)
    // ==========================================

    @JsonProperty("max_tokens")
    private Integer maxTokens;

    private Double temperature;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;
        private String content;
    }
}