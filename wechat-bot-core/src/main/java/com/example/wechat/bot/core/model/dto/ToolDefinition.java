package com.example.wechat.bot.core.model.dto;

import java.util.Map;

/**
 * Function Calling 工具定义，遵循 OpenAI-compatible 格式。
 * <p>
 * 用法：{@code ToolDefinition.of("get_weather", "查询天气", params)}
 *
 * @param type     固定为 "function"
 * @param function 函数定义
 */
public record ToolDefinition(
        String type,
        Function function
) {
    public record Function(
            String name,
            String description,
            Map<String, Object> parameters
    ) {}

    /**
     * 快速创建一个 Function Calling 工具定义
     *
     * @param name        函数名（AI 调用时用的标识）
     * @param description  函数描述（AI 据此决定何时调用）
     * @param parameters   JSON Schema 格式的参数定义
     * @return ToolDefinition
     */
    public static ToolDefinition of(String name, String description, Map<String, Object> parameters) {
        return new ToolDefinition("function", new Function(name, description, parameters));
    }
}
