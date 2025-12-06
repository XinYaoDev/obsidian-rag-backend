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

import org.springframework.core.io.buffer.DataBuffer;

import java.nio.charset.StandardCharsets;

import java.io.IOException;

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



// ✅ 新增：使用 WebClient 处理流式请求 (这是 Spring WebFlux 的核心客户端)

    private final WebClient webClient = WebClient.builder().build();



    private final LlmConfig llmConfig;

    private final ObjectMapper objectMapper = new ObjectMapper();



    /**

     * 普通对话接口 (一次性返回)

     */

    public RagResponse<Object> chat(LlmCompletionRequest request) throws JsonProcessingException {

// 1. 准备配置和 API 地址

        ProviderConfig config = ProviderConfig.fromCode(request.getProvider());

        String apiUrl = config.getBaseUrl() + "/chat/completions";



// 2. 构建通用请求体 (提取了公共逻辑)

        OpenAiRequest requestBody = buildOpenAiRequest(request, config, false);



// 3. 构建 Headers

        HttpHeaders headers = new HttpHeaders();

        headers.setContentType(MediaType.APPLICATION_JSON);

        headers.setBearerAuth(request.getApiKey());



// 🐛 调试日志

        log.debug("普通请求体: {}", objectMapper.writeValueAsString(requestBody));



// 4. 发起请求

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



    public void streamChat(LlmCompletionRequest request, SseEmitter emitter) {

        ProviderConfig config = ProviderConfig.fromCode(request.getProvider());

        String apiUrl = config.getBaseUrl() + "/chat/completions";



// 1. 构建请求体

        OpenAiRequest requestBody = buildOpenAiRequest(request, config, true);



        log.info("🚀 [StreamStart] 开始发起流式请求: {}", apiUrl);



// 使用 StringBuilder 累积可能被分割的行

        StringBuilder lineBuffer = new StringBuilder();



// 2. 使用 WebClient 发起异步流式请求

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

// 将 DataBuffer 转换为字符串

                                String chunk = dataBuffer.toString(StandardCharsets.UTF_8);

                                DataBufferUtils.release(dataBuffer); // 释放缓冲区



// 累积到行缓冲区

                                lineBuffer.append(chunk);



// 按行分割处理（SSE 格式以 \n 或 \n\n 分隔）

                                String bufferStr = lineBuffer.toString();

                                String[] lines = bufferStr.split("\n", -1);



// 保留最后一行（可能不完整）

                                lineBuffer.setLength(0);

                                if (lines.length > 0) {

                                    lineBuffer.append(lines[lines.length - 1]);

                                }



// 处理完整的行

                                for (int i = 0; i < lines.length - 1; i++) {

                                    String line = lines[i].trim();

                                    if (line.isEmpty()) {

                                        continue; // 跳过空行

                                    }



// 处理 SSE 格式：data: {...} 或 data: [DONE]

                                    if (line.startsWith("data: ")) {

                                        String jsonStr = line.substring(6).trim(); // 去掉 "data: " 前缀



                                        if (jsonStr.equals("[DONE]")) {

                                            log.info("🛑 [Handle] 检测到 [DONE] 标识");

                                            continue;

                                        }



// 现在 jsonStr 是纯 JSON，可以传给 handleStreamChunk

                                        log.info("📥 [RawChunk] 收到原始数据片段 (len={}): {}", jsonStr.length(), jsonStr);

                                        handleStreamChunk(jsonStr, emitter);

                                    } else if (line.startsWith("data:")) {

// 处理 "data:" 后面没有空格的情况

                                        String jsonStr = line.substring(5).trim();

                                        if (jsonStr.equals("[DONE]")) {

                                            continue;

                                        }

                                        log.info("📥 [RawChunk] 收到原始数据片段 (len={}): {}", jsonStr.length(), jsonStr);

                                        handleStreamChunk(jsonStr, emitter);

                                    }

// 忽略其他 SSE 字段（如 event:, id: 等）

                                }

                            } catch (Exception e) {

                                DataBufferUtils.release(dataBuffer);

                                log.error("❌ [ProcessError] 处理数据块失败", e);

                            }

                        },

                        error -> {

                            log.error("❌ [StreamError] 流式生成中断/异常", error);

                            try {

                                emitter.send(SseEmitter.event().name("error").data("后端流连接异常: " + error.getMessage()));

                            } catch (IOException e) {

                                log.error("发送错误通知失败", e);

                            }

                            emitter.completeWithError(error);

                        },

                        () -> {

// 处理剩余的缓冲区

                            if (lineBuffer.length() > 0) {

                                String line = lineBuffer.toString().trim();

                                if (line.startsWith("data: ")) {

                                    String jsonStr = line.substring(6).trim();

                                    if (!jsonStr.equals("[DONE]")) {

                                        log.info("📥 [RawChunk] 处理剩余数据 (len={}): {}", jsonStr.length(), jsonStr);

                                        handleStreamChunk(jsonStr, emitter);

                                    }

                                } else if (line.startsWith("data:")) {

                                    String jsonStr = line.substring(5).trim();

                                    if (!jsonStr.equals("[DONE]")) {

                                        log.info("📥 [RawChunk] 处理剩余数据 (len={}): {}", jsonStr.length(), jsonStr);

                                        handleStreamChunk(jsonStr, emitter);

                                    }

                                }

                            }

                            log.info("✅ [StreamDone] 流式请求正常结束");

                            emitter.complete();

                        }

                );

    }



// -------------------------------------------------------------------------

// 私有辅助方法 (核心逻辑复用)

// -------------------------------------------------------------------------



    /**

     * 构建 OpenAiRequest 请求体 (复用逻辑)

     */

    private OpenAiRequest buildOpenAiRequest(LlmCompletionRequest request, ProviderConfig config, boolean isStream) {

        String actualModel = request.getModel() != null && !request.getModel().isEmpty()

                ? request.getModel()

                : config.getDefaultModel();



// 构建 Messages

        List<OpenAiRequest.Message> messages = new ArrayList<>();

        messages.add(OpenAiRequest.Message.builder()

                .role("system")

                .content("你是一个专业的知识库助手。请用中文回答问题。\n\n" +

                        "【重要格式要求】：\n" +

                        "1. 在生成 Markdown 列表时，请务必保持紧凑，**列表项之间不要插入空行**。\n" +

                        "2. 在生成代码块时，请确保代码块完整闭合，格式为：```语言标识\\n代码内容\\n```\n" +

                        "3. 代码块内的内容请保持正确的缩进和格式。\n\n" +

                        "【深度思考模式】（当启用深度思考时）：\n" +

                        "在 `reasoning_content` 字段中，请输出结构化的思考过程。根据问题类型，思考过程应该包括：\n\n" +

                        "### 问题理解\n" +

                        "分析问题的核心要点和需求。\n\n" +

                        "### 分析思路\n" +

                        "梳理解决问题的思路或回答问题的角度，可以包括：\n" +

                        "- 问题特征分析\n" +

                        "- 可能的解决方向\n" +

                        "- 相关知识点或概念\n\n" +

                        "### 解决方案\n" +

                        "列出具体的解决方案或回答要点，每种方案说明核心思路和适用场景。\n\n" +

                        "### 关键要点\n" +

                        "总结重要的实现细节、注意事项或补充说明。\n\n" +

                        "**注意：**\n" +

                        "- 使用 Markdown 格式，保持结构清晰\n" +

                        "- 根据问题复杂度调整思考深度\n" +

                        "- 思考过程应该详细、有条理，帮助用户理解思维过程")

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



// 构建 Builder

        OpenAiRequest.OpenAiRequestBuilder requestBuilder = OpenAiRequest.builder()

                .model(actualModel)

                .messages(messages)

                .stream(isStream);



// 深度思考参数注入

        boolean userWantsThinking = Boolean.TRUE.equals(request.getEnableDeepThinking());

        if (userWantsThinking) {

            if (llmConfig.supportsDeepThinking(actualModel)) {

                log.info("🚀 模型 [{}] 支持深度思考 (Stream:{}), 正在开启参数...", actualModel, isStream);

                if (isAliyunQwen(request.getProvider(), actualModel)) {

                    requestBuilder.enableThinking(true);

                } else if (isDeepSeek(actualModel)) {

                    requestBuilder.reasoningEffort("high");

                }

            } else {

                log.warn("⚠️ 模型 [{}] 不支持深度思考配置，已忽略。", actualModel);

            }

        }



        return requestBuilder.build();

    }



    /**

     * 处理流式响应的每一块数据 (Chunk)

     * 注意：现在接收的是已经解析好的 JSON 字符串，不再是包含 "data: " 前缀的原始行

     */

    private void handleStreamChunk(String jsonStr, SseEmitter emitter) {

        if (jsonStr == null || jsonStr.trim().isEmpty() || jsonStr.equals("[DONE]")) {

            return;

        }



        try {

            JsonNode node = objectMapper.readTree(jsonStr);

            JsonNode choices = node.get("choices");



            if (choices != null && !choices.isEmpty()) {

                JsonNode delta = choices.get(0).get("delta");



// 1. 提取思考过程

// ⚠️ 修复：检查字段是否存在且不为 null

                JsonNode reasoningNode = delta.get("reasoning_content");

                if (reasoningNode != null && !reasoningNode.isNull()) {

                    String reasoning = reasoningNode.asText();

                    if (reasoning != null && !reasoning.isEmpty() && !reasoning.equals("null")) {

                        log.info("🧠 [Emit] 推送思考: {}", reasoning.substring(0, Math.min(10, reasoning.length())) + "...");

                        emitter.send(SseEmitter.event().name("thinking").data(reasoning));

                    }

                }



// 2. 提取正文内容

// ⚠️ 同样修复：检查字段是否存在且不为 null

                JsonNode contentNode = delta.get("content");

                if (contentNode != null && !contentNode.isNull()) {

                    String content = contentNode.asText();

                    if (content != null && !content.isEmpty()) {

                        log.info("📝 [Emit] 推送正文: {}", content.substring(0, Math.min(10, content.length())) + "...");

// 调试：检查是否包含换行符

                        log.info("📝 [info] Content 包含换行符: {}", content.contains("\n"));

                        log.info("📝 [info] Content 前100字符: {}", content.substring(0, Math.min(100, content.length())));

                        emitter.send(SseEmitter.event().name("answer").data(content));

                    }

                }

            }

        } catch (IOException e) {

            log.warn("⚠️ [ParseError] 解析 JSON 失败. Raw: {}", jsonStr, e);

        } catch (Exception e) {

            log.error("❌ [UnknownError] 处理 Chunk 发生未知错误", e);

        }

    }



    /**

     * 处理普通同步响应

     */

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

        log.error("LLM Client Error: {}", e.getMessage());

        String msg = switch (e.getStatusCode().value()) {

            case 400 -> "🚫 参数错误: 模型可能不支持当前的思考参数配置";

            case 401 -> "🚫 鉴权失败：API Key 无效";

            case 404 -> "❓ 模型不存在：" + model;

            case 429 -> "⏳ 请求过于频繁或余额不足";

            default -> "❌ 客户端错误: " + e.getStatusCode();

        };

        return RagResponse.error(msg);

    }



    private boolean isAliyunQwen(String provider, String model) {

        String p = provider != null ? provider.toLowerCase() : "";

        String m = model != null ? model.toLowerCase() : "";

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