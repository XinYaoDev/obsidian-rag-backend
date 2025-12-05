package com.agent.rag.ragbackend.controller;

import com.agent.rag.ragbackend.dto.request.ChatRequest;
import com.agent.rag.ragbackend.dto.request.SyncRequest;
import com.agent.rag.ragbackend.dto.response.RagResponse;
import com.agent.rag.ragbackend.service.LlmService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rag")
@CrossOrigin(origins = "*") // 🔥 核心：必须允许跨域，否则 Obsidian 会报 Network Error
public class RagController {

    @Autowired
    private LlmService llmService; // 注入服务

    @PostMapping("/sync")
    public ResponseEntity<String> syncFile(@RequestBody SyncRequest request) {
        // 1. 简单的日志，证明连通性
        System.out.println("========================================");
        System.out.println("收到 Obsidian 同步请求:");
        System.out.println("文件: " + request.getTitle());
        System.out.println("路径: " + request.getPath());
        System.out.println("内容长度: " + (request.getContent() != null ? request.getContent().length() : 0));
        System.out.println("========================================");

        // 2. 这里留空，以后你可以接入 Kafka/MinIO/ES 逻辑
        // service.process(request);

        return ResponseEntity.ok("Sync Success");
    }


    @PostMapping("/chat")
    public ResponseEntity<RagResponse<String>> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey
    ) {
        // 1. 结构化日志，与 Sync 保持一致
        System.out.println("========================================");
        System.out.println("收到 Obsidian 对话请求:");
        System.out.println("问题内容: " + request.getQuestion());
        System.out.println("选择厂商: " + request.getProvider());
        System.out.println("目标模型: " + request.getModel());
        System.out.println("========================================");

        // ✅ 修正后的历史记录打印 (防止空指针，并打印具体内容)
        if (request.getHistory() != null) {
            int historySize = request.getHistory().size();
            System.out.println("对话历史条数: " + request.getHistory().size());
            // 你的 HistoryMessage 类加上 @Data 后会自动生成 toString，这里可以直接打印
            System.out.println("对话历史详情: " + request.getHistory().get(historySize-1));
        } else {
            System.out.println("对话历史: NULL (这是第一条消息)");
        }

        // 2. 调用 LLM 服务
        // 注意：如果前端没传 Key (比如用 Ollama)，这里 apiKey 可能是 null，Service 层会处理
        // 调用 Service
        RagResponse<String> result = llmService.chat(
                request.getProvider(),
                request.getModel(),
                apiKey,
                request.getQuestion(),
                request.getHistory() // ✅ 把历史传进去
        );

        // 统一返回 HTTP 200，具体的成功/失败看 body 里的 success 字段
        return ResponseEntity.ok(result);
    }
}
