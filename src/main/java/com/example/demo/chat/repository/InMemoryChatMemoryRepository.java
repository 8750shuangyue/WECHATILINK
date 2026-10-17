package com.example.demo.chat.repository;

import com.example.demo.chat.ChatHistoryEntry;
import com.example.demo.chat.ChatMessage;
import com.example.demo.chat.HistoryPage;
import com.example.demo.chat.SourceRefCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class InMemoryChatMemoryRepository implements ChatMemoryRepository {

    private final ConcurrentHashMap<String, List<StoredMessage>> memory = new ConcurrentHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    @Override
    public List<ChatMessage> getMessages(String userId, String conversationId) {
        List<StoredMessage> stored = memory.getOrDefault(key(userId, conversationId), new ArrayList<>());
        List<ChatMessage> messages = new ArrayList<>(stored.size());
        for (StoredMessage entry : stored) {
            messages.add(entry.message());
        }
        return messages;
    }

    @Override
    public void saveMessages(String userId, String conversationId, List<ChatMessage> messages) {
        List<StoredMessage> stored = new ArrayList<>();
        for (ChatMessage message : messages) {
            stored.add(new StoredMessage(idSequence.incrementAndGet(), message, null));
        }
        memory.put(key(userId, conversationId), stored);
    }

    @Override
    public void addMessage(String userId, String conversationId, ChatMessage message) {
        addMessage(userId, conversationId, message, null);
    }

    @Override
    public void addMessage(String userId, String conversationId, ChatMessage message,
                           String sourcesJson) {
        memory.compute(key(userId, conversationId), (key, existing) -> {
            List<StoredMessage> list = existing != null ? existing : new ArrayList<>();
            list.add(new StoredMessage(idSequence.incrementAndGet(), message, sourcesJson));
            return list;
        });
    }

    @Override
    public void clear(String userId, String conversationId) {
        memory.remove(key(userId, conversationId));
    }

    @Override
    public boolean exists(String userId, String conversationId) {
        return memory.containsKey(key(userId, conversationId));
    }

    @Override
    public HistoryPage getHistory(String userId, String conversationId, Long beforeId, int limit) {
        List<StoredMessage> stored = memory.getOrDefault(key(userId, conversationId), List.of());
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<StoredMessage> newestFirst = new ArrayList<>();
        for (int i = stored.size() - 1; i >= 0; i--) {
            StoredMessage entry = stored.get(i);
            ChatMessage message = entry.message();
            if (!"user".equals(message.getRole()) && !"assistant".equals(message.getRole())) {
                continue;
            }
            if (beforeId != null && entry.id() >= beforeId) {
                continue;
            }
            newestFirst.add(entry);
            if (newestFirst.size() > safeLimit) {
                break;
            }
        }

        boolean hasMore = newestFirst.size() > safeLimit;
        List<StoredMessage> page = new ArrayList<>(
                newestFirst.subList(0, Math.min(newestFirst.size(), safeLimit)));
        Long nextBeforeId = hasMore && !page.isEmpty()
                ? page.get(page.size() - 1).id()
                : null;
        Collections.reverse(page);

        List<ChatHistoryEntry> entries = new ArrayList<>(page.size());
        for (StoredMessage entry : page) {
            ChatMessage message = entry.message();
            entries.add(new ChatHistoryEntry(
                    entry.id(),
                    message.getRole(),
                    message.getContent(),
                    message.getTimestamp(),
                    SourceRefCodec.fromJson(entry.sourcesJson())
            ));
        }
        return new HistoryPage(conversationId, entries, hasMore, nextBeforeId);
    }

    @Override
    public void removeSystemMessages(String userId, String conversationId, String contentPrefix) {
        memory.computeIfPresent(key(userId, conversationId), (key, messages) -> {
            messages.removeIf(entry -> {
                ChatMessage msg = entry.message();
                return "system".equals(msg.getRole())
                        && msg.getContent() != null && msg.getContent().startsWith(contentPrefix);
            });
            return messages;
        });
    }

    private String key(String userId, String conversationId) {
        return userId + "\u001F" + conversationId;
    }

    private record StoredMessage(long id, ChatMessage message, String sourcesJson) {
    }
}
