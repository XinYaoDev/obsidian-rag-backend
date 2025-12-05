package com.agent.rag.ragbackend.dto.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RagResponse<T> {
    private boolean success;    // 业务是否成功
    private String message;     // 提示消息 (成功是 "OK"，失败是 "API Key 错误" 等)
    private T data;            // 真正的载荷 (比如 AI 的回复内容)
    private long timestamp;     // 时间戳

    // 快速构建成功响应
    public static <T> RagResponse<T> success(T data) {
        return RagResponse.<T>builder()
                .success(true)
                .message("Success")
                .data(data)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    // 快速构建失败响应
    public static <T> RagResponse<T> error(String errorMessage) {
        return RagResponse.<T>builder()
                .success(false)
                .message(errorMessage)
                .data(null)
                .timestamp(System.currentTimeMillis())
                .build();
    }
}
