package com.agent.rag.ragbackend.controller;

import com.agent.rag.ragbackend.dto.request.ChatRequest;
import com.agent.rag.ragbackend.dto.request.LlmCompletionRequest; // 引入新定义的DTO
import com.agent.rag.ragbackend.dto.request.SyncRequest;
import com.agent.rag.ragbackend.dto.response.RagResponse;
import com.agent.rag.ragbackend.service.LlmService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/rag")
@CrossOrigin(origins = "*")
@Slf4j // ✅ 1. 使用 Lombok 日志注解 (阿里规范：禁止使用 System.out)
@RequiredArgsConstructor // ✅ 2. 使用构造器注入 (阿里规范推荐，代替字段上的 @Autowired)
public class RagController {

    private final LlmService llmService; // final 修饰，确保不可变

    @PostMapping("/sync")
    public ResponseEntity<String> syncFile(@RequestBody SyncRequest request) {
        // 使用占位符打印日志
        log.info("收到 Obsidian 同步请求 - 文件: {}, 路径: {}", request.getTitle(), request.getPath());

        // service.process(request);
        return ResponseEntity.ok("Sync Success");
    }

    @PostMapping("/chat")
    public ResponseEntity<RagResponse<Object>> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey
    ) throws JsonProcessingException {
        // 1. 打印业务日志
        log.info("收到对话请求 - 大模型厂商: {}, 模型名称: {}, 用户提问: {}，历史记录条数:{}，深度思考：{}",
                request.getProvider(), request.getModel(), request.getQuestion(),request.getHistory().size(),request.getEnableDeepThinking());

        // 2. 核心转换逻辑：ChatRequest -> LlmCompletionRequest
        // 2.1 安全地处理历史记录转换 (防止 null)
        List<LlmCompletionRequest.LlmMessage> llmHistory = Optional.ofNullable(request.getHistory())
                .orElse(Collections.emptyList()) // 如果为 null 则返回空列表
                .stream()
                .map(msg -> LlmCompletionRequest.LlmMessage.builder()
                        .role(msg.getRole())
                        .content(msg.getContent())
                        .build())
                .collect(Collectors.toList());

        // 2.2 构建后端请求对象
        LlmCompletionRequest llmRequest = LlmCompletionRequest.builder()
                .provider(request.getProvider())
                .model(request.getModel())
                .apiKey(apiKey) // 这里注入 Key
                .prompt(request.getQuestion())
                .context(llmHistory) // 注入转换后的历史
                .enableDeepThinking(request.getEnableDeepThinking())
                .build();

        // 3. 调用 Service (现在 Service 接收的是干净的后端 DTO)
        RagResponse<Object> result = llmService.chat(llmRequest);

        return ResponseEntity.ok(result);
    }
}