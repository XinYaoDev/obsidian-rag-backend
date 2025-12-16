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
     * 流式对话接口（SSE）
     */
    public void streamChat(LlmCompletionRequest request, SseEmitter emitter) {
        ProviderConfig config = ProviderConfig.fromCode(request.getProvider());
        // 优先使用前端传入的baseUrl，如果没有则使用默认的
        String baseUrl = request.getBaseUrl() != null ? request.getBaseUrl() : config.getBaseUrl();
        String apiUrl = baseUrl + "/chat/completions";

        OpenAiRequest requestBody = buildOpenAiRequest(request, config, true);
        log.info("🚀 [StreamStart] 开始发起流式请求: {}", apiUrl);

        StringBuilder lineBuffer = new StringBuilder();

        webClient.post()
                .uri(apiUrl)
                .header("Authorization", "Bearer " + request.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchangeToFlux(response -> {
                    if (response.statusCode().is2xxSuccessful()) {
                        return response.bodyToFlux(DataBuffer.class);
                    } else {
                        log.error("❌ [StreamError] 下游API返回错误状态: {}", response.statusCode());
                        return Flux.error(new RuntimeException("下游API错误: " + response.statusCode()));
                    }
                })
                .subscribe(
                        dataBuffer -> {
                            try {
                                String chunk = dataBuffer.toString(StandardCharsets.UTF_8);
                                DataBufferUtils.release(dataBuffer);

                                lineBuffer.append(chunk);

                                // 循环处理缓冲区中的每一行
                                int newlineIndex;
                                while ((newlineIndex = lineBuffer.indexOf("\n")) != -1) {
                                    String line = lineBuffer.substring(0, newlineIndex).trim();
                                    lineBuffer.delete(0, newlineIndex + 1); // 移除已处理的行

                                    if (line.isEmpty()) continue;
                                    processLine(line, emitter);
                                }
                            } catch (Exception e) {
                                log.error("❌ [ProcessError] 处理数据块失败", e);
                            }
                        },
                        error -> {
                            log.error("❌ [StreamError] 流式生成中断/异常", error);
                            try {
                                // 发送 JSON 格式的错误信息
                                Map<String, String> errorMap = new HashMap<>();
                                errorMap.put("error", "后端流连接异常: " + error.getMessage());
                                emitter.send(SseEmitter.event().name("error").data(errorMap));
                            } catch (IOException e) {
                                log.error("发送错误通知失败", e);
                            }
                            emitter.completeWithError(error);
                        },
                        () -> {
                            // 处理剩余的 buffer（防止最后一行没有换行符）
                            if (lineBuffer.length() > 0) {
                                processLine(lineBuffer.toString().trim(), emitter);
                            }
                            log.info("✅ [StreamDone] 流式请求正常结束");
                            emitter.complete();
                        }
                );
    }

    /**
     * 统一处理单行数据逻辑
     */
    private void processLine(String line, SseEmitter emitter) {
        if (line.startsWith("data:")) {
            String jsonStr = line.substring(5).trim();
            if ("[DONE]".equals(jsonStr)) {
                log.info("🛑 [Handle] 检测到 [DONE] 标识");
                return;
            }
            // log.debug("📥 [RawChunk] 处理数据: {}", jsonStr); // 减少日志量，只在 debug 开启
            handleStreamChunk(jsonStr, emitter);
        }
    }

    /**
     * 处理流式响应的每一块数据（Chunk）
     * ✨ 核心修改：将数据封装为 Map 后再发送，确保换行符和空格不丢失 ✨
     */
    private void handleStreamChunk(String jsonStr, SseEmitter emitter) {
        if (jsonStr == null || jsonStr.trim().isEmpty()) {
            return;
        }

        try {
            JsonNode node = objectMapper.readTree(jsonStr);
            JsonNode choices = node.get("choices");

            if (choices != null && !choices.isEmpty()) {
                JsonNode delta = choices.get(0).get("delta");

                // 1. 提取并推送思考过程
                JsonNode reasoningNode = delta.get("reasoning_content");
                if (reasoningNode != null && !reasoningNode.isNull()) {
                    String reasoning = reasoningNode.asText();
                    if (reasoning != null && !reasoning.isEmpty()) {
                        // 封装成 Map，Spring 会自动序列化为 JSON 字符串
                        // 传输格式示例: data: {"content": "我正在思考...\n第二行"}
                        Map<String, String> dataMap = new HashMap<>();
                        dataMap.put("content", reasoning);

                        emitter.send(SseEmitter.event()
                                .name("thinking")
                                .data(dataMap, MediaType.APPLICATION_JSON));
                    }
                }

                // 2. 提取并推送正文内容
                JsonNode contentNode = delta.get("content");
                if (contentNode != null && !contentNode.isNull()) {
                    String content = contentNode.asText();
                    if (content != null && !content.isEmpty()) {
                        // 封装成 Map
                        Map<String, String> dataMap = new HashMap<>();
                        dataMap.put("content", content);

                        // 发送 JSON，确保特殊字符（\n, \t, 空格）被正确转义传输
                        emitter.send(SseEmitter.event()
                                .name("answer")
                                .data(dataMap, MediaType.APPLICATION_JSON));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("⚠️ [ParseError] 解析 JSON 失败. Raw: {}", jsonStr);
        } catch (Exception e) {
            log.error("❌ [UnknownError] 处理 Chunk 发生未知错误", e);
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