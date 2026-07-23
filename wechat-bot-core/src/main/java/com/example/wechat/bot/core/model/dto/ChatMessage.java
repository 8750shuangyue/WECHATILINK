package com.example.wechat.bot.core.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatMessage(
        String role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId
) {

    /**
     * 用于 user / system / assistant（无 tool_calls）的简洁构造
     */
    public ChatMessage(String role, String content) {
        this(role, content, null, null);
    }

    /**
     * 用于 tool 角色（返回工具执行结果）
     *
     * @param toolCallId 对应 tool_calls 条目中的 id
     * @param content    工具执行结果
     */
    public static ChatMessage toolResponse(String toolCallId, String content) {
        return new ChatMessage("tool", content, null, toolCallId);
    }

    /**
     * AI 发起的工具调用
     *
     * @param id       调用 ID（关联 tool response）
     * @param type     固定 "function"
     * @param function 函数名和参数
     */
    public record ToolCall(
            String id,
            String type,
            FunctionCall function
    ) {}

    /**
     * 函数调用信息
     *
     * @param name      函数名
     * @param arguments JSON 字符串形式的参数
     */
    public record FunctionCall(
            String name,
            String arguments
    ) {}
}
