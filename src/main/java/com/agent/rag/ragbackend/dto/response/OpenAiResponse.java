package com.agent.rag.ragbackend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpenAiResponse {

    private List<Choice> choices;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Choice {
        private Message message;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Message {
        private String role;

        private String content;

        /**
         * 🔥 核心修改：添加 @JsonProperty 注解
         * 作用：告诉 Jackson 把 JSON 里的 "reasoning_content" 字段赋值给这里的 thinking 变量。
         * 适用模型：DeepSeek R1, 阿里云 QwQ/Qwen-Plus (开启 enable_thinking 时)
         */
        @JsonProperty("reasoning_content")
        private String thinking;
    }
}