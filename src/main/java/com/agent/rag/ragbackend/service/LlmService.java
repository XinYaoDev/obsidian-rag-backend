package com.agent.rag.ragbackend.service;

import com.agent.rag.ragbackend.config.LlmConfig;
import com.agent.rag.ragbackend.config.ProviderConfig;
import com.agent.rag.ragbackend.dto.request.LlmCompletionRequest;
import com.agent.rag.ragbackend.dto.request.OpenAiRequest;
import com.agent.rag.ragbackend.dto.response.OpenAiResponse;
import com.agent.rag.ragbackend.dto.response.RagResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final WebClient webClient = WebClient.builder().build();
    private final LlmConfig llmConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Qualifier("dbExecutor")
    private final Executor dbExecutor;

    public RagResponse<Object> chat(LlmCompletionRequest request) throws JsonProcessingException {
        // (保持原有的 chat 代码逻辑不变)
        // 为了节省篇幅，这里省略 chat 方法的具体实现，仅展示修改的核心部分
        ProviderConfig config = ProviderConfig.fromCode(request.getProvider());
        // 优先使用前端传入的baseUrl，如果没有则使用默认的
        String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : config.getBaseUrl();
        String apiUrl = baseUrl + "/chat/completions";
        OpenAiRequest requestBody = buildOpenAiRequest(request, config, false);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(request.getApiKey());
        try {
            HttpEntity<OpenAiRequest> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<OpenAiResponse> response = restTemplate.postForEntity(apiUrl, entity, OpenAiResponse.class);
            if (response.getBody() != null && !response.getBody().getChoices().isEmpty()) {
                OpenAiResponse.Message message = response.getBody().getChoices().get(0).getMessage();
                return processSyncResponse(message);
            }
            return RagResponse.error("⚠️ 模型返回了空内容");
        } catch (HttpClientErrorException e) {
            return handleClientError(e, requestBody.getModel());
        } catch (Exception e) {
            log.error("LLM System Error", e);
            return RagResponse.error("🐞 系统错误: " + e.getMessage());
        }
    }

    /**
     * 流式对话接口（SSE） + 异步入库
     * 修复了 Null 问题，并整合了思考过程的存储
     */
    public void streamChat(LlmCompletionRequest request, SseEmitter emitter) {
        ProviderConfig config = ProviderConfig.fromCode(request.getProvider());
        String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : config.getBaseUrl();
        String apiUrl = baseUrl + "/chat/completions";

        OpenAiRequest requestBody = buildOpenAiRequest(request, config, true);
        log.info("🚀 [StreamStart] 开始发起流式请求: {}", apiUrl);

        // 1. 定义累加器 (必须在 WebClient 请求之前定义)
        // 用于拼接正文回复
        StringBuilder fullResponseBuilder = new StringBuilder();
        // 用于拼接深度思考内容 (DeepSeek/Qwen 等)
        StringBuilder thinkingBuilder = new StringBuilder();

        String conversationId = request.getConversationId();
        StringBuilder lineBuffer = new StringBuilder();

        // 2. 发起 WebClient 请求
        webClient.post()
                .uri(apiUrl)
                .header("Authorization", "Bearer " + request.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                // 必须使用 exchangeToFlux 来处理响应流
                .exchangeToFlux(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToFlux(DataBuffer.class);
                    } else {
                        log.error("❌ [StreamError] 下游API返回错误状态: {}", response.statusCode());
                        return Flux.error(new RuntimeException("下游API错误: " + response.statusCode()));
                    }
                })
                .subscribe(
                        // A. 处理数据流 (OnNext)
                        dataBuffer -> {
                            try {
                                String chunk = dataBuffer.toString(StandardCharsets.UTF_8);
                                DataBufferUtils.release(dataBuffer); // 释放内存
                                lineBuffer.append(chunk);

                                int newlineIndex;
                                // 循环处理每一行 (解决 TCP 粘包问题)
                                while ((newlineIndex = lineBuffer.indexOf("\n")) != -1) {
                                    String line = lineBuffer.substring(0, newlineIndex).trim();
                                    lineBuffer.delete(0, newlineIndex + 1);

                                    if (line.isEmpty()) continue;

                                    // ✨ 核心修改：将 Builder 传入，一边发 SSE 一边存内存
                                    processLine(line, emitter, fullResponseBuilder, thinkingBuilder);
                                }
                            } catch (Exception e) {
                                log.error("❌ [ProcessError] 处理数据块失败", e);
                            }
                        },
                        // B. 处理错误 (OnError)
                        error -> {
                            log.error("❌ [StreamError] 流式生成中断/异常", error);
                            try {
                                Map<String, String> errorMap = new HashMap<>();
                                errorMap.put("error", "后端流连接异常: " + error.getMessage());
                                emitter.send(SseEmitter.event().name("error").data(errorMap));
                            } catch (IOException e) {
                                log.error("发送错误通知失败", e);
                            }
                            emitter.completeWithError(error);
                        },
                        // C. 处理完成 (OnComplete)
                        () -> {
                            // 处理缓冲区剩余的最后一行
                            if (lineBuffer.length() > 0) {
                                processLine(lineBuffer.toString().trim(), emitter, fullResponseBuilder, thinkingBuilder);
                            }

                            log.info("✅ [StreamDone] 流式请求正常结束");
                            emitter.complete(); // 关闭前端连接

                            // 3. 构造入库内容
                            String finalContent;
                            // 如果有思考过程，按 DeepSeek 格式拼接
                            if (thinkingBuilder.length() > 0) {
                                finalContent = String.format("<think>\n%s\n</think>\n%s",
                                        thinkingBuilder.toString(), fullResponseBuilder.toString());
                            } else {
                                finalContent = fullResponseBuilder.toString();
                            }

                            // 4. 异步提交到数据库线程池
                            if (!finalContent.isEmpty()) {
                                String finalContentRef = finalContent; // 确保在 Lambda 中有效
                                CompletableFuture.runAsync(() -> {
                                    saveToDatabase(conversationId, finalContentRef);
                                }, dbExecutor); // ⚠️ 确保注入了 dbExecutor
                            }
                        }
                );
    }

    /**
     * 处理单行数据
     * 注意：方法签名已修改，增加了两个 StringBuilder 参数
     */
    private void processLine(String line, SseEmitter emitter, StringBuilder contentBuilder, StringBuilder thinkingBuilder) {
        if (line.startsWith("data:")) {
            String jsonStr = line.substring(5).trim();
            if ("[DONE]".equals(jsonStr)) {
                return; // 结束标志，忽略
            }
            handleStreamChunk(jsonStr, emitter, contentBuilder, thinkingBuilder);
        }
    }

    /**
     * 处理具体的 JSON 数据块
     * 这里同时负责：1. 推送给前端 2. 累加到 StringBuilder
     */
    private void handleStreamChunk(String jsonStr, SseEmitter emitter, StringBuilder contentBuilder, StringBuilder thinkingBuilder) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) return;

        try {
            JsonNode node = objectMapper.readTree(jsonStr);
            JsonNode choices = node.get("choices");

            if (choices != null && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).get("delta");
                if (delta == null) return;
                // --- 1. 处理思考过程 (Reasoning) ---
                if (delta.has("reasoning_content")) {
                    JsonNode reasoningNode = delta.get("reasoning_content");
                    if (reasoningNode != null && !reasoningNode.isNull()) {
                        String reasoning = reasoningNode.asText();
                        if (!reasoning.isEmpty()) {
                            // A. 存入内存
                            thinkingBuilder.append(reasoning);
                            // B. 推送前端
                            Map<String, String> dataMap = new HashMap<>();
                            dataMap.put("content", reasoning);
                            emitter.send(SseEmitter.event().name("thinking").data(dataMap));
                        }
                    }
                }
                // --- 2. 处理正文内容 (Content) ---
                if (delta.has("content")) {
                    JsonNode contentNode = delta.get("content");
                    // ⚠️ 关键修正：必须判断 !isNull()，否则 append "null" 字符串
                    if (contentNode != null && !contentNode.isNull()) {
                        String content = contentNode.asText();
                        if (!content.isEmpty()) {
                            // A. 存入内存
                            contentBuilder.append(content);
                            // B. 推送前端
                            Map<String, String> dataMap = new HashMap<>();
                            dataMap.put("content", content);
                            emitter.send(SseEmitter.event().name("answer").data(dataMap));
                        }
                    }
                }
            }
        } catch (IOException e) {
            // 如果连接断开，日志记 warn 即可，不要抛出异常打断流的接收
            log.warn("⚠️ [SSE] 推送前端失败 (可能是用户关闭了连接): {}", e.getMessage());
        } catch (Exception e) {
            log.error("❌ [Parse] 处理 Chunk 异常", e);
        }
    }

    /**
     * 【新增】模拟入库方法
     */
    private void saveToDatabase(String conversationId, String content) {
        try {
            log.info("💾 [DB] 正在异步保存会话,{}:{}", conversationId,content);
            log.info("当前执行入库的线程是: {}", Thread.currentThread().getName());
            // 这里调用你的 Repository
            // messageRepository.save(new Message(conversationId, "assistant", content));
        } catch (Exception e) {
            log.error("❌ [DB] 保存会话失败", e);
        }
    }

    private OpenAiRequest buildOpenAiRequest(LlmCompletionRequest request, ProviderConfig config, boolean isStream) {
        // (逻辑保持不变，参考你原本的代码)
        String actualModel = (request.getModel() != null && !request.getModel().isEmpty())
                ? request.getModel()
                : config.getDefaultModel();

        List<OpenAiRequest.Message> messages = new ArrayList<>();
        // ... System Prompt 逻辑 ...
        messages.add(OpenAiRequest.Message.builder()
                .role("system")
                .content("你是一个专业的知识库助手...") // 简化
                .build());

        List<LlmCompletionRequest.LlmMessage> context = request.getContext();
        if (context != null && !context.isEmpty()) {
            int start = Math.max(0, context.size() - 20);
            for (int i = start; i < context.size(); i++) {
                LlmCompletionRequest.LlmMessage msg = context.get(i);
                messages.add(OpenAiRequest.Message.builder()
                        .role(msg.getRole())
                        .content(msg.getContent())
                        .build());
            }
        }

        messages.add(OpenAiRequest.Message.builder()
                .role("user")
                .content(request.getPrompt())
                .build());

        OpenAiRequest.OpenAiRequestBuilder requestBuilder = OpenAiRequest.builder()
                .model(actualModel)
                .messages(messages)
                .stream(isStream);

        boolean userWantsThinking = Boolean.TRUE.equals(request.getEnableDeepThinking());
        if (userWantsThinking) {
            if (llmConfig.supportsDeepThinking(actualModel)) {
                if (isAliyunQwen(request.getProvider(), actualModel)) {
                    requestBuilder.enableThinking(true);
                } else if (isDeepSeek(actualModel)) {
                    requestBuilder.reasoningEffort("high");
                }
            }
        }
        return requestBuilder.build();
    }

    private RagResponse<Object> processSyncResponse(OpenAiResponse.Message message) {
        String rawContent = message.getContent();
        String thinkingContent = message.getThinking();
        String finalAnswer = rawContent != null ? rawContent : "";
        String finalThinking = "";

        if (thinkingContent != null && !thinkingContent.isEmpty()) {
            finalThinking = thinkingContent;
        } else if (rawContent != null && rawContent.contains("<think>")) {
            finalThinking = extractThinkContent(rawContent);
            finalAnswer = removeThinkTags(rawContent);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("answer", finalAnswer);
        if (!finalThinking.isEmpty()) {
            result.put("thinking", finalThinking);
        }
        return RagResponse.success(result);
    }

    private RagResponse<Object> handleClientError(HttpClientErrorException e, String model) {
        // (保持不变)
        return RagResponse.error("Error: " + e.getMessage());
    }

    private boolean isAliyunQwen(String provider, String model) {
        String p = (provider != null) ? provider.toLowerCase() : "";
        String m = (model != null) ? model.toLowerCase() : "";
        return p.contains("aliyun") || p.contains("qwen") || m.contains("qwen");
    }

    private boolean isDeepSeek(String model) {
        return model != null && model.toLowerCase().contains("deepseek");
    }

    private String extractThinkContent(String text) {
        Pattern pattern = Pattern.compile("(?s)<think>(.*?)</think>");
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private String removeThinkTags(String text) {
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }
}