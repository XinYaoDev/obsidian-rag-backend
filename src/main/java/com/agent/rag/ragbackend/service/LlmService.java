package com.agent.rag.ragbackend.service;

import com.agent.rag.ragbackend.config.LlmConfig;
import com.agent.rag.ragbackend.config.ProviderConfig;
import com.agent.rag.ragbackend.dto.request.LlmCompletionRequest;
import com.agent.rag.ragbackend.dto.request.OpenAiRequest;
import com.agent.rag.ragbackend.dto.response.OpenAiResponse;
import com.agent.rag.ragbackend.dto.response.RagResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

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
    private final LlmConfig llmConfig;
    private final ObjectMapper objectMapper = new ObjectMapper(); // 建议复用

    public RagResponse<Object> chat(LlmCompletionRequest request) throws JsonProcessingException {
        // 1. 配置获取
        ProviderConfig config = ProviderConfig.fromCode(request.getProvider());
        String apiUrl = config.getBaseUrl() + "/chat/completions";
        String actualModel = request.getModel() != null && !request.getModel().isEmpty()
                ? request.getModel()
                : config.getDefaultModel();

        // 2. 构建 Headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(request.getApiKey());

        // 3. 构建 Messages
        List<OpenAiRequest.Message> messages = new ArrayList<>();
        // 3.1 System Prompt
        messages.add(OpenAiRequest.Message.builder()
                .role("system")
                .content("你是一个专业的知识库助手。请用中文回答问题。" +
                        "【重要格式要求】：在生成 Markdown 列表时，" +
                        "请务必保持紧凑，**列表项之间不要插入空行**。")
                .build());

        // 3.2 Context
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

        // 3.3 User Prompt
        messages.add(OpenAiRequest.Message.builder()
                .role("user")
                .content(request.getPrompt())
                .build());

        // -------------------------------------------------------------
        // 🔥 4. 构建 RequestBody (使用新 DTO 字段)
        // -------------------------------------------------------------
        OpenAiRequest.OpenAiRequestBuilder requestBuilder = OpenAiRequest.builder()
                .model(actualModel)
                .messages(messages)
                .stream(false);

        boolean userWantsThinking = Boolean.TRUE.equals(request.getEnableDeepThinking());

        if (userWantsThinking) {
            // 注意：这里检查方法名是否与 LlmConfig 中定义的一致 (supportsReasoning 或 supportsDeepThinking)
            if (llmConfig.supportsDeepThinking(actualModel)) {
                log.info("🚀 模型 [{}] 支持深度思考，正在开启参数...", actualModel);

                if (isAliyunQwen(request.getProvider(), actualModel)) {
                    // ✅ 阿里云专用：直接设置新字段
                    requestBuilder.enableThinking(true);
                    // requestBuilder.thinkingEffort("Medium"); // 可选
                }
                else if (isDeepSeek(actualModel)) {
                    // ✅ DeepSeek专用：直接设置新字段
                    requestBuilder.reasoningEffort("high");
                }
            } else {
                log.warn("⚠️ 用户开启了深度思考，但配置显示模型 [{}] 不支持。已自动忽略。", actualModel);
            }
        }

        OpenAiRequest requestBody = requestBuilder.build();

        // 🐛 调试日志：打印最终 JSON 确保参数在根节点
        log.debug("最终请求体: {}", objectMapper.writeValueAsString(requestBody));

        // 5. 发起请求
        try {
            HttpEntity<OpenAiRequest> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<OpenAiResponse> response = restTemplate.postForEntity(apiUrl, entity, OpenAiResponse.class);

            if (response.getBody() != null && !response.getBody().getChoices().isEmpty()) {
                OpenAiResponse.Message message = response.getBody().getChoices().get(0).getMessage();

                // A. 原始数据获取
                String rawContent = message.getContent();
                // 这是 Jackson 自动从 "reasoning_content" 映射过来的
                String thinkingContent = message.getThinking();

                String finalAnswer = rawContent != null ? rawContent : "";
                String finalThinking = "";

                // B. 双重解析逻辑 (Double Check)

                // 优先使用标准字段 (DeepSeek R1 / 阿里云 QwQ 标准模式)
                if (thinkingContent != null && !thinkingContent.isEmpty()) {
                    finalThinking = thinkingContent;
                    log.info("✅ 通过 reasoning_content 字段获取到思考过程");
                }
                // 兜底方案：如果字段为空，检查 Content 里有没有 <think> 标签 (Qwen 兼容模式)
                else if (rawContent != null && rawContent.contains("<think>")) {
                    finalThinking = extractThinkContent(rawContent);
                    finalAnswer = removeThinkTags(rawContent);
                    log.info("⚠️ 通过 <think> 标签提取到思考过程");
                }

                // C. 构建返回结果
                Map<String, Object> result = new HashMap<>();
                result.put("answer", finalAnswer);

                if (!finalThinking.isEmpty()) {
                    result.put("thinking", finalThinking);
                    // 打印前50个字符避免日志爆炸
                    log.info("🧠 思考预览: {}...", finalThinking.substring(0, Math.min(finalThinking.length(), 50)));
                }

                return RagResponse.success(result);
            }
            return RagResponse.error("⚠️ 模型返回了空内容");

        } catch (HttpClientErrorException e) {
            log.error("LLM Client Error: {} - Body: {}", e.getMessage(), e.getResponseBodyAsString());
            String msg = switch (e.getStatusCode().value()) {
                case 400 -> "🚫 参数错误: 模型可能不支持当前的思考参数配置";
                case 401 -> "🚫 鉴权失败：API Key 无效";
                case 404 -> "❓ 模型不存在：" + requestBody.getModel();
                case 429 -> "⏳ 请求过于频繁或余额不足";
                default -> "❌ 客户端错误: " + e.getStatusCode();
            };
            return RagResponse.error(msg);

        } catch (Exception e) {
            log.error("LLM System Error", e);
            return RagResponse.error("🐞 系统错误: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 辅助工具方法
    // -------------------------------------------------------------------------

    private boolean isAliyunQwen(String provider, String model) {
        String p = provider != null ? provider.toLowerCase() : "";
        String m = model != null ? model.toLowerCase() : "";
        return p.contains("aliyun") || p.contains("qwen") || m.contains("qwen");
    }

    private boolean isDeepSeek(String model) {
        return model != null && model.toLowerCase().contains("deepseek");
    }

    /**
     * 从文本中提取 <think>...</think> 内容
     */
    private String extractThinkContent(String text) {
        Pattern pattern = Pattern.compile("(?s)<think>(.*?)</think>");
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return "";
    }

    /**
     * 移除 <think>...</think> 标签及其内容
     */
    private String removeThinkTags(String text) {
        return text.replaceAll("(?s)<think>.*?</think>", "").trim();
    }
}