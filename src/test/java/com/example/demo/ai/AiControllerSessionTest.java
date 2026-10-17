package com.example.demo.ai;

import com.example.demo.care.service.CareRecordService;
import com.example.demo.care.service.CareReminderService;
import com.example.demo.care.service.MedicalTriageService;
import com.example.demo.care.service.SpringAiCareWorkflowService;
import com.example.demo.chat.ChatHistoryEntry;
import com.example.demo.chat.ChatMemoryService;
import com.example.demo.chat.HistoryPage;
import com.example.demo.chat.LlmService;
import com.example.demo.chat.RagContextService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    void historyRequiresLogin() {
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        AiController controller = createController(mock(LlmService.class), chatMemoryService);

        var response = controller.getHistory(50, null, new MockHttpSession());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(Boolean.FALSE, response.getBody().get("success"));
        verifyNoInteractions(chatMemoryService);
    }

    @Test
    void historyReturnsEmptyPageWhenSessionHasNoConversation() {
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        when(chatMemoryService.getHistory("user-a", null, null, 50))
                .thenReturn(new HistoryPage(null, List.of(), false, null));
        AiController controller = createController(mock(LlmService.class), chatMemoryService);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");

        var response = controller.getHistory(50, null, session);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(Boolean.TRUE, response.getBody().get("success"));
        assertNull(response.getBody().get("conversationId"));
        assertTrue(((List<?>) response.getBody().get("messages")).isEmpty());
        assertEquals(Boolean.FALSE, response.getBody().get("hasMore"));
    }

    @Test
    void historyClampsLimitAndReturnsStoredSources() {
        ChatMemoryService chatMemoryService = mock(ChatMemoryService.class);
        HistoryPage page = new HistoryPage("conversation-a", List.of(
                new ChatHistoryEntry(1L, "user", "问题", "2026-10-17T12:00:00", List.of()),
                new ChatHistoryEntry(2L, "assistant", "回答[1]", "2026-10-17T12:00:01",
                        List.of(new RagContextService.SourceRef("kb_a", "标题 A", 1)))
        ), false, null);
        when(chatMemoryService.getHistory("user-a", "conversation-a", 5L, 100))
                .thenReturn(page);
        AiController controller = createController(mock(LlmService.class), chatMemoryService);
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");
        session.setAttribute("conversationId", "conversation-a");

        var response = controller.getHistory(500, 5L, session);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("conversation-a", response.getBody().get("conversationId"));
        assertEquals(2, ((List<?>) response.getBody().get("messages")).size());
        verify(chatMemoryService).getHistory("user-a", "conversation-a", 5L, 100);
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

    @Test
    void emitsRagSourcesOnlyWhenRequested() throws Exception {
        LlmService llmService = mock(LlmService.class);
        doAnswer(invocation -> {
            Consumer<String> trace = invocation.getArgument(4);
            Consumer<List<RagContextService.SourceRef>> sources = invocation.getArgument(5);
            Consumer<String> token = invocation.getArgument(6);
            Runnable done = invocation.getArgument(7);
            trace.accept("trace-1");
            sources.accept(List.of(
                    new RagContextService.SourceRef("kb_plant_watering", "浇水与积水")));
            token.accept("hello");
            done.run();
            return null;
        }).when(llmService).chatStream(
                any(), any(), any(), any(), any(), any(), any(), any());
        AiController controller = createController(llmService, mock(ChatMemoryService.class));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult result = mockMvc.perform(post("/api/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\",\"includeSources\":\"true\"}")
                        .session(session))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(2_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("event:rag-sources"));
        assertTrue(body.indexOf("event:rag-trace") < body.indexOf("event:rag-sources"));
        assertTrue(body.indexOf("event:rag-sources") < body.indexOf("hello"));
        assertTrue(body.contains("kb_plant_watering"));
        assertFalse(body.contains("documentId"));
    }

    @Test
    void includeCitationsForcesRagSourcesEvent() throws Exception {
        LlmService llmService = mock(LlmService.class);
        doAnswer(invocation -> {
            Consumer<String> trace = invocation.getArgument(4);
            Consumer<List<RagContextService.SourceRef>> sources = invocation.getArgument(5);
            Consumer<String> token = invocation.getArgument(6);
            Runnable done = invocation.getArgument(7);
            trace.accept("trace-1");
            sources.accept(List.of(
                    new RagContextService.SourceRef("kb_plant_watering", "浇水与积水", 1)));
            token.accept("hello[1]");
            done.run();
            return null;
        }).when(llmService).chatStream(
                any(), any(), any(), any(), any(), any(), any(), any(), eq(true));
        AiController controller = createController(llmService, mock(ChatMemoryService.class));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult result = mockMvc.perform(post("/api/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\",\"includeCitations\":\"true\"}")
                        .session(session))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(2_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("event:rag-sources"));
        assertTrue(body.contains("\"rank\":1"));
        assertTrue(body.contains("hello[1]"));
    }

    @Test
    void omitsRagSourcesWhenFlagIsAbsent() throws Exception {
        LlmService llmService = mock(LlmService.class);
        doAnswer(invocation -> {
            Consumer<String> trace = invocation.getArgument(4);
            Consumer<List<RagContextService.SourceRef>> sources = invocation.getArgument(5);
            Consumer<String> token = invocation.getArgument(6);
            Runnable done = invocation.getArgument(7);
            trace.accept("trace-1");
            sources.accept(List.of(
                    new RagContextService.SourceRef("kb_plant_watering", "浇水与积水")));
            token.accept("hello");
            done.run();
            return null;
        }).when(llmService).chatStream(
                any(), any(), any(), any(), any(), any(), any(), any());
        AiController controller = createController(llmService, mock(ChatMemoryService.class));
        MockHttpSession session = new MockHttpSession();
        session.setAttribute("user", "user-a");
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        MvcResult result = mockMvc.perform(post("/api/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hello\"}")
                        .session(session))
                .andExpect(request().asyncStarted())
                .andReturn();
        result.getAsyncResult(2_000);
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk());

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("event:rag-sources"));
        assertTrue(body.contains("hello"));
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
