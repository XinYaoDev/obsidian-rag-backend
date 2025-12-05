package com.agent.rag.ragbackend.dto.request;

import lombok.Data;

import java.io.Serializable;

/**
 * 请求参数基类
 * 封装公共字段和序列化接口
 */
@Data
public abstract class BaseRequest implements Serializable {

    // 1. 基类定义序列化 ID
    private static final long serialVersionUID = 1L;

    // 2. 这里可以放所有请求通用的字段 (可选)
    // 例如：请求链路追踪ID，或者请求来源
    // private String requestId;
    // private String clientSource;
}
