package com.agent.rag.ragbackend.controller;

import com.agent.rag.ragbackend.dto.request.ChatRequest;
import com.agent.rag.ragbackend.dto.request.LlmCompletionRequest;
import com.agent.rag.ragbackend.dto.request.SyncRequest;
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
@RequestMapping("/api/rag")
@CrossOrigin(origins = "*")
@Slf4j
@RequiredArgsConstructor
public class RagController {

    private final LlmService llmService;

    @PostMapping("/sync")
    public ResponseEntity<String> syncFile(@RequestBody SyncRequest request) {
        log.info("收到 Obsidian 同步请求 - 文件: {}, 路径: {}", request.getTitle(), request.getPath());
        // service.process(request);
        return ResponseEntity.ok("Sync Success");
    }

    /**
     * 普通对话接口 (等待全部生成完一次性返回)
     */
    @PostMapping("/chat")
    public ResponseEntity<RagResponse<Object>> chat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey
    ) throws JsonProcessingException {
        // 1. 转换请求
        LlmCompletionRequest llmRequest = convertToLlmRequest(request, apiKey);

        log.info("收到普通对话请求 - 模型: {}, 深度思考: {}", request.getModel(), request.getEnableDeepThinking());

        // 2. 调用 Service
        RagResponse<Object> result = llmService.chat(llmRequest);

        return ResponseEntity.ok(result);
    }

    /**
     * ✅ 新增：流式对话接口 (SSE)
     * 响应类型必须是 text/event-stream
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @RequestBody ChatRequest request,
            @RequestHeader(value = "X-API-KEY", required = false) String apiKey
    ) {
        log.info("收到流式对话请求 (SSE) - 模型: {}, 深度思考: {}", request.getModel(), request.getEnableDeepThinking());

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
     * 🧪 专用测试接口：模拟流式输出 (不调用大模型)
     * 用途：测试前端是否能正确接收 SSE 流，以及中间件(Nginx)是否有缓冲问题
     * 请求方式：POST /api/rag/test/stream
     */
    @PostMapping(value = "/test/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter testStream(@RequestBody ChatRequest request) { // 保持和正式接口一样的参数结构，方便前端直接切换
        log.info("🧪 收到测试流请求，开始模拟数据...");

        // 1. 创建 Emitter (永不超时)
        SseEmitter emitter = new SseEmitter(0L);

        // 2. 异步执行模拟任务
        CompletableFuture.runAsync(() -> {
            try {
                // === 阶段一：模拟深度思考 (Thinking) ===
                String[] thinkingSteps = {
                        "正在检索知识库...",
                        "发现相关文档: 'Java并发编程.pdf'...",
                        "正在规划回答逻辑...",
                        "思考完毕，准备生成答案。"
                };

                for (String step : thinkingSteps) {
                    // 模拟网络延迟
                    Thread.sleep(800);
                    // 发送 thinking 事件
                    emitter.send(SseEmitter.event().name("thinking").data(step));
                    log.info("🧪 推送思考: {}", step);
                }

                // === 阶段二：模拟打字机回答 (Answer) ===
                String mockAnswer = "你好！这是一个用于测试 **流式输出 (SSE)** 的模拟回复。\n\n" +
                        "如果你能看到这段文字像打字机一样逐字出现，说明你的：\n" +
                        "1. 前端 fetch 读取逻辑是正确的。\n" +
                        "2. 后端 SseEmitter 配置是正确的。\n" +
                        "3. Nginx/网关没有拦截缓冲流数据。\n\n" +
                        "测试结束。🚀";

                // 将答案拆分为字符，模拟 token 生成
                for (char c : mockAnswer.toCharArray()) {
                    Thread.sleep(50); // 每个字间隔 50ms
                    emitter.send(SseEmitter.event().name("answer").data(String.valueOf(c)));
                }

                // === 结束 ===
                log.info("🧪 测试流结束");
                emitter.complete();

            } catch (Exception e) {
                log.error("🧪 测试流异常", e);
                emitter.completeWithError(e);
            }
        });

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
                .apiKey(apiKey)
                .prompt(request.getQuestion())
                .context(llmHistory)
                .enableDeepThinking(request.getEnableDeepThinking())
                .build();
    }
}