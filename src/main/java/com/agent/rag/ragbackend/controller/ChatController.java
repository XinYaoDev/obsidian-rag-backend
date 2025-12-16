package com.agent.rag.ragbackend.controller;


import com.agent.rag.ragbackend.dto.request.ChatRequest;
import com.agent.rag.ragbackend.dto.request.LlmCompletionRequest;
import com.agent.rag.ragbackend.dto.response.RagResponse;
import com.agent.rag.ragbackend.service.LlmService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class ChatController {

    private final LlmService llmService;

    /**
     * 普通对话接口 (等待全部生成完一次性返回)
     */
    @PostMapping
    public ResponseEntity<RagResponse<Object>> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey
    ) throws JsonProcessingException {
        // 1. 转换请求
        LlmCompletionRequest llmRequest = convertToLlmRequest(request, apiKey);

        log.info("收到普通对话请求 - 模型: {}, 深度思考: {},提问：{}", request.getModel(), request.getEnableDeepThinking(),request.getQuestion());

        // 2. 调用 Service
        RagResponse<Object> result = llmService.chat(llmRequest);

        return ResponseEntity.ok(result);
    }

    /**
     * ✅ 新增：流式对话接口 (SSE)
     * 响应类型必须是 text/event-stream
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey
    ) {
        log.info("收到流式对话请求 (SSE) - 模型: {}, 深度思考: {},提问：{}", request.getModel(), request.getEnableDeepThinking(),request.getQuestion());

        // 1. 创建 SseEmitter (0L 表示永不超时，防止 AI 回答时间过长导致连接断开)
        SseEmitter emitter = new SseEmitter(0L);

        // 2. 转换请求对象
        LlmCompletionRequest llmRequest = convertToLlmRequest(request, apiKey);

        // 3. 异步调用 Service，防止阻塞主线程
        CompletableFuture.runAsync(() -> {
            try {
                // 注意：你需要确保 LlmService 中已经实现了这个 streamChat 方法
                llmService.streamChat(llmRequest, emitter);
            } catch (Exception e) {
                log.error("流式生成异常", e);
                emitter.completeWithError(e);
            }
        });

        // 4. 直接返回 emitter，建立连接
        return emitter;
    }

    /**
     * 🛠️ 辅助方法：将前端 ChatRequest 转换为后端 LlmCompletionRequest
     * 提取出来复用，避免代码重复
     */
    private LlmCompletionRequest convertToLlmRequest(ChatRequest request, String apiKey) {
        // 安全地处理历史记录转换
        List<LlmCompletionRequest.LlmMessage> llmHistory = Optional.ofNullable(request.getHistory())
                .orElse(Collections.emptyList())
                .stream()
                .map(msg -> LlmCompletionRequest.LlmMessage.builder()
                        .role(msg.getRole())
                        .content(msg.getContent())
                        .build())
                .collect(Collectors.toList());

        return LlmCompletionRequest.builder()
                .provider(request.getProvider())
                .model(request.getModel())
                .baseUrl(request.getBaseUrl())
                .apiKey(apiKey)
                .prompt(request.getQuestion())
                .context(llmHistory)
                .enableDeepThinking(request.getEnableDeepThinking())
                .build();
    }
}
