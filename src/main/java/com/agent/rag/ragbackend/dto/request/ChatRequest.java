package com.agent.rag.ragbackend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

/**
 * 聊天接口请求参数
 */
@Data
@EqualsAndHashCode(callSuper = true)  // 比较子类的equals&&Hash的同时也比较父类的
public class ChatRequest extends BaseRequest {

    private static final long serialVersionUID = 1L;

   /**
    * 用户提问的问题
    * (建议添加校验，不允许为空)
    */
    @NotBlank(message = "提问内容不能为空")
    private String question;

    /**
     * 大模型厂商 (e.g., aliyun, openai)
     */
    private String provider;

    /**
     * 模型具体名称 (e.g., qwen-max)
     */
    private String model;

    /**
     * 是否开启深度思考
     * (推荐赋默认值，防止空指针)
     */
    private Boolean enableDeepThinking = false;

    /**
     * 历史对话记录
     */
    private List<HistoryMessage> history;

    @Data
    public static class HistoryMessage implements Serializable {
        private static final long serialVersionUID = 1L;
        private String role;
        private String content;
    }
}