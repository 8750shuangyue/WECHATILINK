package com.example.demo.chat;

import com.example.demo.chat.repository.ChatMemoryRepository;
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
                "conversation-a",
                "My dog is named Doudou and is 5 years old.",
                "I will remember that."
        );

        List<ChatMessage> sameConversation = service.buildPromptMessages(
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
                "conversation-b",
                null,
                "What is my dog's name and age?"
        );
        assertEquals(1, otherConversation.size());
        assertEquals("What is my dog's name and age?", otherConversation.get(0).getContent());
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
    }

    private static final class InMemoryChatMemoryRepository implements ChatMemoryRepository {
        private final Map<String, List<ChatMessage>> conversations = new LinkedHashMap<>();

        @Override
        public List<ChatMessage> getMessages(String conversationId) {
            return List.copyOf(conversations.getOrDefault(conversationId, List.of()));
        }

        @Override
        public void saveMessages(String conversationId, List<ChatMessage> messages) {
            conversations.put(conversationId, new ArrayList<>(messages));
        }

        @Override
        public void addMessage(String conversationId, ChatMessage message) {
            conversations.computeIfAbsent(conversationId, ignored -> new ArrayList<>()).add(message);
        }

        @Override
        public void clear(String conversationId) {
            conversations.remove(conversationId);
        }

        @Override
        public boolean exists(String conversationId) {
            return conversations.containsKey(conversationId);
        }

        @Override
        public void removeSystemMessages(String conversationId, String contentPrefix) {
            List<ChatMessage> messages = conversations.get(conversationId);
            if (messages != null) {
                messages.removeIf(message -> "system".equals(message.getRole())
                        && message.getContent() != null
                        && message.getContent().startsWith(contentPrefix));
            }
        }
    }
}
