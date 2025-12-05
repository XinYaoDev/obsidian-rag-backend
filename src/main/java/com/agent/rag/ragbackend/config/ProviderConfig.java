package com.agent.rag.ragbackend.config;

import lombok.Getter;

@Getter
public enum ProviderConfig {

    // 阿里云通义千问 (兼容版接口)
    ALIYUN("aliyun", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-turbo"),

    // DeepSeek
    DEEPSEEK("deepseek", "https://api.deepseek.com", "deepseek-coder"),

    // Moonshot (Kimi)
    MOONSHOT("moonshot", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),

    // OpenAI 官方
    OPENAI("openai", "https://api.openai.com/v1", "gpt-3.5-turbo"),

    // Ollama 本地
    OLLAMA("ollama", "http://localhost:11434/v1", "llama3");

    private final String code;
    private final String baseUrl;
    private final String defaultModel;

    ProviderConfig(String code, String baseUrl, String defaultModel) {
        this.code = code;
        this.baseUrl = baseUrl;
        this.defaultModel = defaultModel;
    }

    public static ProviderConfig fromCode(String code) {
        for (ProviderConfig config : values()) {
            if (config.code.equalsIgnoreCase(code)) return config;
        }
        return OLLAMA; // 默认回退
    }
}