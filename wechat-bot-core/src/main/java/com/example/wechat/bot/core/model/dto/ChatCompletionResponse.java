package com.example.wechat.bot.core.model.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatCompletionResponse(
        List<ChatChoice> choices
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatChoice(
            ChatMessage message,
            @JsonProperty("finish_reason") String finishReason
    ) {
    }
}
