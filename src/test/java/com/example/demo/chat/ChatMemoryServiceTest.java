package com.example.demo.chat;

import com.example.demo.chat.repository.ChatMemoryRepository;
import com.example.demo.chat.event.SummaryUpdateEvent;
import com.example.demo.chat.event.VectorSaveEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemoryServiceTest {

    private ChatMemoryService service;
    private List<Object> events;
    private InMemoryChatMemoryRepository repository;
    private VectorStoreService vectorStoreService;
    private UserSessionService userSessionService;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        repository = new InMemoryChatMemoryRepository();
        vectorStoreService = mock(VectorStoreService.class);
        userSessionService = mock(UserSessionService.class);
        service = new ChatMemoryService(
                repository,
                events::add,
                userSessionService
        );
        ReflectionTestUtils.setField(service, "vectorStoreService", vectorStoreService);
        ReflectionTestUtils.setField(service, "maxMessages", 10);
        ReflectionTestUtils.setField(service, "maxTokens", 10_000L);
        ReflectionTestUtils.setField(service, "summaryThreshold", 100_000L);
        ReflectionTestUtils.setField(service, "summaryKeepRecent", 5);
    }

    @Test
    void keepsHistoryPerConversationAndDoesNotLeakAcrossConversations() {
        service.saveMessagePair(
                "user-a",
                "conversation-a",
                "My dog is named Doudou and is 5 years old.",
                "I will remember that."
        );

        List<ChatMessage> sameConversation = service.buildPromptMessages(
                "user-a",
                "conversation-a",
                "Be concise.",
                "What is my dog's name and age?"
        );
        assertEquals(4, sameConversation.size());
        assertEquals("system", sameConversation.get(0).getRole());
        assertEquals("My dog is named Doudou and is 5 years old.", sameConversation.get(1).getContent());
        assertEquals("I will remember that.", sameConversation.get(2).getContent());
        assertEquals("What is my dog's name and age?", sameConversation.get(3).getContent());

        List<ChatMessage> otherConversation = service.buildPromptMessages(
                "user-a",
                "conversation-b",
                null,
                "What is my dog's name and age?"
        );
        assertEquals(1, otherConversation.size());
        assertEquals("What is my dog's name and age?", otherConversation.get(0).getContent());
    }

    @Test
    void keepsHistoryIsolatedWhenDifferentUsersReuseConversationId() {
        service.saveMessagePair(
                "user-a",
                "shared-conversation",
                "The secret code is alpha.",
                "I will remember alpha."
        );

        List<ChatMessage> userBPrompt = service.buildPromptMessages(
                "user-b",
                "shared-conversation",
                null,
                "What is the secret code?"
        );

        assertEquals(1, userBPrompt.size());
        assertEquals("What is the secret code?", userBPrompt.get(0).getContent());
    }

    @Test
    void publishesUserIdWithVectorSaveEvent() {
        service.saveMessagePair("user-a", "conversation-a", "hello", "hi");

        VectorSaveEvent event = events.stream()
                .filter(VectorSaveEvent.class::isInstance)
                .map(VectorSaveEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("user-a", event.getUserId());
        assertEquals("conversation-a", event.getConversationId());

        SummaryUpdateEvent summaryEvent = events.stream()
                .filter(SummaryUpdateEvent.class::isInstance)
                .map(SummaryUpdateEvent.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("user-a", summaryEvent.getUserId());
        assertEquals("conversation-a", summaryEvent.getConversationId());
    }

    @Test
    void clearConversationClearsMessagesVectorsAndUserSessionState() {
        service.saveMessagePair("user-a", "conversation-a", "hello", "hi");

        assertTrue(service.hasConversation("user-a", "conversation-a"));

        service.clearConversation("user-a", "conversation-a");

        assertFalse(service.hasConversation("user-a", "conversation-a"));
        verify(vectorStoreService).clearConversationVectors("user-a", "conversation-a");
        verify(userSessionService).clearSession("user-a");
    }

    @Test
    void skipsSummaryWriteWhenConversationClearedDuringGeneration() throws Exception {
        ReflectionTestUtils.setField(service, "summaryThreshold", 0L);
        ReflectionTestUtils.setField(service, "summaryKeepRecent", 1);

        LlmService llmService = mock(LlmService.class);
        when(llmService.chat(anyString(), anyString())).thenAnswer(invocation -> {
            repository.clear("user-a", "conversation-a");
            return "摘要内容";
        });
        ReflectionTestUtils.setField(service, "llmService", llmService);

        service.saveMessagePair("user-a", "conversation-a", "hello", "hi");
        service.saveMessagePair("user-a", "conversation-a", "how are you", "fine");

        service.checkAndUpdateSummary("user-a", "conversation-a");

        assertTrue(service.getConversationHistory("user-a", "conversation-a").isEmpty());
    }

    private static final class InMemoryChatMemoryRepository implements ChatMemoryRepository {
        private final Map<String, List<ChatMessage>> conversations = new LinkedHashMap<>();

        @Override
        public List<ChatMessage> getMessages(String userId, String conversationId) {
            return List.copyOf(conversations.getOrDefault(key(userId, conversationId), List.of()));
        }

        @Override
        public void saveMessages(String userId, String conversationId, List<ChatMessage> messages) {
            conversations.put(key(userId, conversationId), new ArrayList<>(messages));
        }

        @Override
        public void addMessage(String userId, String conversationId, ChatMessage message) {
            conversations.computeIfAbsent(key(userId, conversationId), ignored -> new ArrayList<>()).add(message);
        }

        @Override
        public void clear(String userId, String conversationId) {
            conversations.remove(key(userId, conversationId));
        }

        @Override
        public boolean exists(String userId, String conversationId) {
            return conversations.containsKey(key(userId, conversationId));
        }

        @Override
        public void removeSystemMessages(String userId, String conversationId, String contentPrefix) {
            List<ChatMessage> messages = conversations.get(key(userId, conversationId));
            if (messages != null) {
                messages.removeIf(message -> "system".equals(message.getRole())
                        && message.getContent() != null
                        && message.getContent().startsWith(contentPrefix));
            }
        }

        private String key(String userId, String conversationId) {
            return userId + "\u0000" + conversationId;
        }
    }
}
