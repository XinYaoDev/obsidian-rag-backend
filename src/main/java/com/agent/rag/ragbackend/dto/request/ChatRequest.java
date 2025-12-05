package com.agent.rag.ragbackend.dto.request;
import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {
    private String question;
    private String provider;
    private String model; // ✅ 接收前端传来的 llmModelName

    // ✅ 新增：接收前端传来的历史记录
    private List<HistoryMessage> history;

    @Data
    public static class HistoryMessage {
        private String role;    // "user" 或 "assistant"
        private String content;
    }

}
