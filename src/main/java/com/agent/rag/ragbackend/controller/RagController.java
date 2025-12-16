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
}