package com.example.demo.ai;

import com.example.demo.care.service.CareRecordService;
import com.example.demo.care.service.CareReminderService;
import com.example.demo.care.service.MedicalTriageService;
import com.example.demo.care.service.SpringAiCareWorkflowService;
import com.example.demo.chat.ChatMemoryService;
import com.example.demo.chat.LlmService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AiControllerSessionTest {

    @Test
    void reusesConversationWithinSessionAndIsolatesDifferentSessions() throws Exception {
        LlmService llmService = mock(LlmService.class);
        AiController controller = createController(llmService, mock(ChatMemoryService.class));
        MockHttpSession firstSession = new MockHttpSession();
        firstSession.setAttribute("user", "user-a");
        MockHttpSession secondSession = new MockHttpSession();
        secondSession.setAttribute("user", "user-b");

        controller.chatStream(Map.of("message", "first"), firstSession);
        controller.chatStream(Map.of("message", "second"), firstSession);
        controller.chatStream(Map.of("message", "third"), secondSession);

        ArgumentCaptor<String> userIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> conversationIds = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> messages = ArgumentCaptor.forClass(String.class);
        verify(llmService, timeout(2_000).times(3)).chatStream(
                userIds.capture(),
                conversationIds.capture(),
                messages.capture(),
                isNull(),
                any(Consumer.class),
                any(Consumer.class),
                any(Runnable.class)
        );

        Map<String, String> conversationByMessage = zipToMap(
                messages.getAllValues(),
                conversationIds.getAllValues()
        );
        Map<String, String> userByMessage = zipToMap(
                messages.getAllValues(),
                userIds.getAllValues()
        );
        assertEquals(conversationByMessage.get("first"), conversationByMessage.get("second"));
        assertNotEquals(conversationByMessage.get("first"), conversationByMessage.get("third"));
        assertEquals("user-a", userByMessage.get("first"));
        assertEquals("user-a", userByMessage.get("second"));
        assertEquals("user-b", userByMessage.get("third"));
    }

    @Test
    void compatibilityChatDoesNotUsePersistentMemory() {
        SpringAiChatService springAiChatService = mock(SpringAiChatService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        when(springAiChatService.chat("user-a", "single turn", "single turn system", null))
                .thenReturn("single reply");
        AiController controller = createController(
                springAiChatService,
                mock(ToolCallingService.class),
                mock(LlmService.class),
                chatMemoryService
        );
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");

        var response = controller.chat(
                Map.of("message", "single turn", "systemPrompt", "single turn system"),
                session
        );

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        assertEquals("single reply", response.getBody().get("content"));
        verify(springAiChatService).chat("user-a", "single turn", "single turn system", null);
        verifyNoInteractions(chatMemoryService);
    }

    @Test
    void compatibilityToolChatDoesNotUsePersistentMemory() {
        SpringAiChatService springAiChatService = mock(SpringAiChatService.class);
        ToolCallingService toolCallingService = mock(ToolCallingService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        when(toolCallingService.validateToolNames(any())).thenReturn(List.of());
        when(springAiChatService.chatWithTools("user-a", "use a tool", null, null))
                .thenReturn(ToolCallResponse.builder().text("tool reply").build());
        AiController controller = createController(
                springAiChatService,
                toolCallingService,
                mock(LlmService.class),
                chatMemoryService
        );
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userName", "user-a");

        var response = controller.chatWithTools(Map.of("message", "use a tool"), request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        assertEquals("tool reply", response.getBody().get("content"));
        verify(springAiChatService).chatWithTools("user-a", "use a tool", null, null);
        verifyNoInteractions(chatMemoryService);
    }

    @Test
    void clearConversationClearsBackendDataAndRotatesConversationId() throws Exception {
        LlmService llmService = mock(LlmService.class);
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        AiController controller = createController(llmService, chatMemoryService);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");
        session.setAttribute("conversationId", "old-conversation");

        var response = controller.clearConversation(session);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        verify(chatMemoryService).clearConversation("user-a", "old-conversation");
        assertNull(session.getAttribute("conversationId"));

        controller.chatStream(Map.of("message", "after clear"), session);

        ArgumentCaptor<String> conversationId = ArgumentCaptor.forClass(String.class);
        verify(llmService, timeout(2_000)).chatStream(
                eq("user-a"),
                conversationId.capture(),
                eq("after clear"),
                isNull(),
                any(Consumer.class),
                any(Consumer.class),
                any(Runnable.class)
        );
        assertNotEquals("old-conversation", conversationId.getValue());
    }

    @Test
    void failedClearKeepsConversationIdForRetry() {
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        AiController controller = createController(mock(LlmService.class), chatMemoryService);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");
        session.setAttribute("conversationId", "old-conversation");
        doThrow(new IllegalStateException("vector cleanup failed"))
                .when(chatMemoryService)
                .clearConversation("user-a", "old-conversation");

        var response = controller.clearConversation(session);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals("old-conversation", session.getAttribute("conversationId"));
    }

    @Test
    void clearWithoutConversationClearsOnlyUserSessionState() {
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        AiController controller = createController(mock(LlmService.class), chatMemoryService);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");

        var response = controller.clearConversation(session);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(chatMemoryService).clearUserSession("user-a");
        verify(chatMemoryService, never()).clearConversation(any(), any());
    }

    private AiController createController(LlmService llmService, ChatMemoryService chatMemoryService) {
        return createController(
                mock(SpringAiChatService.class),
                mock(ToolCallingService.class),
                llmService,
                chatMemoryService
        );
    }

    private AiController createController(SpringAiChatService springAiChatService,
                                          ToolCallingService toolCallingService,
                                          LlmService llmService,
                                          ChatMemoryService chatMemoryService) {
        return new AiController(
                springAiChatService,
                toolCallingService,
                mock(SpringAiCareWorkflowService.class),
                mock(CareRecordService.class),
                mock(CareReminderService.class),
                mock(MedicalTriageService.class),
                llmService,
                chatMemoryService
        );
    }

    private Map<String, String> zipToMap(List<String> keys, List<String> values) {
        return keys.stream().collect(Collectors.toMap(
                key -> key,
                key -> values.get(keys.indexOf(key))
        ));
    }
}
