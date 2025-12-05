package com.agent.rag.ragbackend.service;


import com.agent.rag.ragbackend.config.ProviderConfig;
import com.agent.rag.ragbackend.dto.request.ChatRequest;
import com.agent.rag.ragbackend.dto.request.OpenAiRequest;
import com.agent.rag.ragbackend.dto.response.OpenAiResponse;
import com.agent.rag.ragbackend.dto.response.RagResponse;
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
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class LlmService {

    private final RestTemplate restTemplate = new RestTemplate();

    public RagResponse<String> chat(String providerCode, String model, String apiKey, String question,List<ChatRequest.HistoryMessage> history) {
        ProviderConfig config = ProviderConfig.fromCode(providerCode);
        String apiUrl = config.getBaseUrl() + "/chat/completions";

        // ... (Header 和 Body 的构造逻辑保持不变) ...
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        // 🔥 构造 Prompt：加入 System Prompt (系统指令)
        List<OpenAiRequest.Message> messages = new ArrayList<>();

        // 1. 系统指令：强制要求紧凑排版
        messages.add(OpenAiRequest.Message.builder()
                .role("system")
                .content("你是一个专业的知识库助手。请用中文回答问题。" +
                        "【重要格式要求】：在生成 Markdown 列表（无序列表或有序列表）时，" +
                        "请务必保持紧凑，**列表项之间不要插入空行**。" +
                        "不要输出松散列表，以确保在客户端渲染时排版整洁。")
                .build());

        // 2. 【第二层】插入历史记录 (让模型知道上下文)
        if (history != null && !history.isEmpty()) {
            // 限制一下历史记录长度（比如只保留最近 10 条），防止 Token 爆炸
            int start = Math.max(0, history.size() - 10);
            for (int i = start; i < history.size(); i++) {
                ChatRequest.HistoryMessage hist = history.get(i);
                messages.add(OpenAiRequest.Message.builder()
                        .role(hist.getRole())
                        .content(hist.getContent())
                        .build());
            }
        }

        // 3. 【第三层】当前问题
        messages.add(OpenAiRequest.Message.builder()
                .role("user")
                .content(question)
                .build());

        OpenAiRequest requestBody = OpenAiRequest.builder()
                .model(model != null && !model.isEmpty() ? model : config.getDefaultModel())
                .messages(messages) // 使用新的 messages 列表
                .stream(false)
                .build();


        try {
            HttpEntity<OpenAiRequest> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<OpenAiResponse> response = restTemplate.postForEntity(apiUrl, entity, OpenAiResponse.class);

            if (response.getBody() != null && !response.getBody().getChoices().isEmpty()) {
                String content = response.getBody().getChoices().get(0).getMessage().getContent();
                // ✅ 成功返回结构体
                return RagResponse.success(content);
            }
            return RagResponse.error("⚠️ 模型返回了空内容");

        } catch (HttpClientErrorException e) {
            log.error("LLM Client Error: {}", e.getMessage());
            String msg = switch (e.getStatusCode().value()) {
                case 401 -> "🚫 鉴权失败：请检查 API Key";
                case 404 -> "❓ 模型不存在：" + requestBody.getModel();
                case 429 -> "⏳ 余额不足或请求过快";
                default -> "❌ 客户端错误: " + e.getStatusCode();
            };
            // ✅ 失败返回结构体
            return RagResponse.error(msg);

        } catch (HttpServerErrorException e) {
            log.error("LLM Server Error: {}", e.getMessage());
            return RagResponse.error("💥 服务商 (" + providerCode + ") 崩溃，请稍后重试");

        } catch (Exception e) {
            log.error("LLM Unknown Error", e);
            return RagResponse.error("🐞 系统未知错误: " + e.getMessage());
        }
    }
}
