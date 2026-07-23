package com.example.wechat.bot.core.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCompletionRequest(
        String model,
        List<ChatMessage> messages,
        double temperature,
        List<ToolDefinition> tools
) {
    public ChatCompletionRequest(String model, List<ChatMessage> messages, double temperature) {
        this(model, messages, temperature, null);
    }
}
