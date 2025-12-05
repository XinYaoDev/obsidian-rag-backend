package com.agent.rag.ragbackend.dto.request;

import lombok.Builder;
import lombok.Data;
import lombok.ToString;
import java.io.Serializable;
import java.util.List;

/**
 * LLM 服务调用请求封装
 * 用于 Service 层向 Infrastructure 层传输数据
 *
 * @author Gemini
 */
@Data
@Builder
public class LlmCompletionRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 大模型厂商 (e.g., Qwen, OpenAI)
     */
    private String provider;

    /**
     * 模型名称 (e.g., qwen-max)
     */
    private String model;

    /**
     * API Key
     * ⚠️ 安全规约：敏感信息禁止在 toString 中打印
     */
    @ToString.Exclude
    private String apiKey;

    /**
     * 当前用户提问 (Prompt)
     */
    private String prompt;

    /**
     * 上下文历史消息
     */
    private List<LlmMessage> context;

    /**
     * 深度思考开关
     */
    @Builder.Default
    private Boolean enableDeepThinking = false;

    // 如果后续有 temperature, topP 等参数，直接加在这里，无需改接口
    // private Double temperature;

    /**
     * LLM 专用消息体定义
     * (与前端 ChatRequest.HistoryMessage 解耦，防止前端改动影响底层)
     */
    @Data
    @Builder
    public static class LlmMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        private String role;
        private String content;
    }
}
