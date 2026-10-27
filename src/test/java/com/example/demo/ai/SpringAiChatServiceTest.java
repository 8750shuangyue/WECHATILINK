package com.example.demo.ai;

import com.example.demo.chat.VectorStoreService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SpringAiChatServiceTest {

    @Test
    void chatReturnsModelTextInsteadOfDebugString() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("你好"));

        SpringAiChatService service = new SpringAiChatService(
                chatModel, mock(VectorStoreService.class), mock(ToolCallingService.class));

        String result = service.chat("hi");

        assertEquals("你好", result);
        assertFalse(result.contains("AssistantMessage"));
    }

    @Test
    void chatWithTemplateReturnsModelText() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(response("模板输出"));

        SpringAiChatService service = new SpringAiChatService(
                chatModel, mock(VectorStoreService.class), mock(ToolCallingService.class));

        String result = service.chatWithTemplate("你好 {name}", Map.of("name", "小明"));

        assertEquals("模板输出", result);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
