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

class ChatMemoryServiceTest {

    private ChatMemoryService service;
    private List<Object> events;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        service = new ChatMemoryService(
                new InMemoryChatMemoryRepository(),
                events::add
        );
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
