package com.agent.rag.ragbackend.dto.request;

import lombok.Data;

@Data
public class SyncRequest {
    private String title;     // 文件名 (file.basename)
    private String path;      // 文件路径 (file.path)
    private String content;   // 文件全文
    private String embeddingProvider; // ✅ 接收 aliyun/openai
    private String embeddingModel;    // ✅ 接收 text-embedding-v1
}
