package com.agent.rag.ragbackend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/conversation")
@RequiredArgsConstructor
public class ConversationController {

    /**
     * 获取所有会话列表
     * @return
     */
    @GetMapping
    public String getConversation() {
        return "getConversation";
    }

    /**
     * 获取指定会话详情
     * @param conversationId 会话ID
     * @return
     */
    @GetMapping("/{conversationId}")
    public String getConversationDetail(@PathVariable String conversationId) {
        return "getConversationDetail";
    }
}
