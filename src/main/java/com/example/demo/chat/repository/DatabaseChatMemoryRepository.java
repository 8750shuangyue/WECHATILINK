package com.example.demo.chat.repository;

import com.example.demo.chat.ChatHistoryEntry;
import com.example.demo.chat.ChatMessage;
import com.example.demo.chat.HistoryPage;
import com.example.demo.chat.SourceRefCodec;
import com.example.demo.chat.entity.Conversation;
import com.example.demo.chat.entity.Message;
import com.example.demo.chat.exception.ConversationAccessDeniedException;
import com.example.demo.chat.repository.mysql.ConversationRepository;
import com.example.demo.chat.repository.mysql.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class DatabaseChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseChatMemoryRepository.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final String SYSTEM_ROLE = "system";
    private static final List<String> HISTORY_ROLES = List.of("user", "assistant");

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public DatabaseChatMemoryRepository(ConversationRepository conversationRepository, 
                                        MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(String userId, String conversationId) {
        requireIdentifiers(userId, conversationId);
        logger.info("Database getMessages - conversationId: {}", conversationId);
        if (!isConversationOwnedBy(userId, conversationId)) {
            logger.warn("Database getMessages - access denied or unowned conversation: {}", conversationId);
            return List.of();
        }

        List<Message> messages = messageRepository.findByConversationIdOrderByTimestampAsc(conversationId);
        List<ChatMessage> chatMessages = new ArrayList<>();
        for (Message message : messages) {
            chatMessages.add(convertToChatMessage(message));
        }
        logger.info("Database getMessages - loaded {} messages for conversationId: {}", 
                    chatMessages.size(), conversationId);
        return chatMessages;
    }

    @Override
    @Transactional
    public void saveMessages(String userId, String conversationId, List<ChatMessage> messages) {
        logger.info("Database saveMessages - conversationId: {}, messages count: {}", 
                    conversationId, messages.size());
        
        ensureConversationOwnedBy(userId, conversationId);
        
        messageRepository.deleteByConversationId(conversationId);
        
        for (ChatMessage chatMessage : messages) {
            messageRepository.save(convertToMessage(conversationId, chatMessage));
        }
        
        logger.info("Database saveMessages - completed");
    }

    @Override
    @Transactional
    public void addMessage(String userId, String conversationId, ChatMessage message) {
        addMessage(userId, conversationId, message, null);
    }

    @Override
    @Transactional
    public void addMessage(String userId, String conversationId, ChatMessage message,
                           String sourcesJson) {
        logger.info("Database addMessage - conversationId: {}, role: {}", conversationId, message.getRole());

        ensureConversationOwnedBy(userId, conversationId);

        Message entity = convertToMessage(conversationId, message, sourcesJson);
        messageRepository.save(entity);

        logger.info("Database addMessage - completed");
    }

    @Override
    @Transactional(readOnly = true)
    public HistoryPage getHistory(String userId, String conversationId, Long beforeId, int limit) {
        requireIdentifiers(userId, conversationId);
        if (!isConversationOwnedBy(userId, conversationId)) {
            logger.warn("Database getHistory - access denied or unowned conversation: {}", conversationId);
            return new HistoryPage(conversationId, List.of(), false, null);
        }

        int safeLimit = Math.max(1, Math.min(limit, 100));
        PageRequest pageRequest = PageRequest.of(0, safeLimit + 1);
        List<Message> messages = beforeId == null
                ? messageRepository.findByConversationIdAndRoleInOrderByIdDesc(
                        conversationId, HISTORY_ROLES, pageRequest)
                : messageRepository.findByConversationIdAndRoleInAndIdLessThanOrderByIdDesc(
                        conversationId, HISTORY_ROLES, beforeId, pageRequest);

        boolean hasMore = messages.size() > safeLimit;
        List<Message> page = new ArrayList<>(messages.subList(0, Math.min(messages.size(), safeLimit)));
        Long nextBeforeId = hasMore && !page.isEmpty()
                ? page.get(page.size() - 1).getId()
                : null;
        Collections.reverse(page);

        List<ChatHistoryEntry> entries = new ArrayList<>(page.size());
        for (Message message : page) {
            entries.add(new ChatHistoryEntry(
                    message.getId(),
                    message.getRole(),
                    message.getContent(),
                    message.getTimestamp() == null ? null : message.getTimestamp().format(FORMATTER),
                    SourceRefCodec.fromJson(message.getSourcesJson())
            ));
        }
        logger.info("Database getHistory - loaded {} messages for conversationId: {}",
                entries.size(), conversationId);
        return new HistoryPage(conversationId, entries, hasMore, nextBeforeId);
    }

    @Override
    @Transactional
    public void clear(String userId, String conversationId) {
        logger.info("Database clear - conversationId: {}", conversationId);
        requireIdentifiers(userId, conversationId);
        Optional<Conversation> existing = conversationRepository.findById(conversationId);
        if (existing.isEmpty()) {
            logger.info("Database clear - conversation does not exist, nothing to clear: {}", conversationId);
            return;
        }
        if (!userId.equals(existing.get().getUserId())) {
            throw new ConversationAccessDeniedException(conversationId);
        }
        messageRepository.deleteByConversationId(conversationId);
        logger.info("Database clear - completed");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String userId, String conversationId) {
        requireIdentifiers(userId, conversationId);
        return isConversationOwnedBy(userId, conversationId);
    }

    @Override
    @Transactional
    public void removeSystemMessages(String userId, String conversationId, String contentPrefix) {
        logger.info("Database removeSystemMessages - conversationId: {}, prefix: {}", conversationId, contentPrefix);
        ensureConversationOwnedBy(userId, conversationId);
        messageRepository.deleteByConversationIdAndRoleAndContentStartingWith(conversationId, SYSTEM_ROLE, contentPrefix);
        logger.info("Database removeSystemMessages - completed");
    }

    private void ensureConversationOwnedBy(String userId, String conversationId) {
        requireIdentifiers(userId, conversationId);
        Optional<Conversation> existing = conversationRepository.findById(conversationId);
        if (existing.isEmpty()) {
            conversationRepository.save(new Conversation(conversationId, userId));
            return;
        }

        if (!userId.equals(existing.get().getUserId())) {
            throw new ConversationAccessDeniedException(conversationId);
        }
    }

    private boolean isConversationOwnedBy(String userId, String conversationId) {
        return conversationRepository.findById(conversationId)
                .map(conversation -> userId.equals(conversation.getUserId()))
                .orElse(false);
    }

    private void requireIdentifiers(String userId, String conversationId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
    }

    private ChatMessage convertToChatMessage(Message entity) {
        return new ChatMessage(
                entity.getRole(),
                entity.getContent(),
                entity.getTimestamp().format(FORMATTER)
        );
    }

    private Message convertToMessage(String conversationId, ChatMessage chatMessage) {
        return convertToMessage(conversationId, chatMessage, null);
    }

    private Message convertToMessage(String conversationId, ChatMessage chatMessage, String sourcesJson) {
        LocalDateTime timestamp;
        if (chatMessage.getTimestamp() != null && !chatMessage.getTimestamp().isEmpty()) {
            timestamp = LocalDateTime.parse(chatMessage.getTimestamp(), FORMATTER);
        } else {
            timestamp = LocalDateTime.now();
        }

        return new Message(
                conversationId,
                chatMessage.getRole(),
                chatMessage.getContent(),
                timestamp,
                sourcesJson
        );
    }
}
