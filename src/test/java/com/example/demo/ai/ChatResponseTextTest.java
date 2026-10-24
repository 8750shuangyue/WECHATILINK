package com.example.demo.ai;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChatResponseTextTest {

    @Test
    void extractsPlainTextFromAssistantMessage() {
        ChatResponse response = response("你好，我是助手");

        assertEquals("你好，我是助手", ChatResponseText.extract(response));
    }

    @Test
    void returnsNullForNullResponse() {
        assertNull(ChatResponseText.extract(null));
    }

    @Test
    void returnsFallbackWhenTextIsBlank() {
        ChatResponse response = response("   ");

        assertEquals("fallback", ChatResponseText.extract(response, "fallback"));
    }

    @Test
    void returnsTextEvenWhenFallbackProvided() {
        ChatResponse response = response("ok");

        assertEquals("ok", ChatResponseText.extract(response, "fallback"));
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
