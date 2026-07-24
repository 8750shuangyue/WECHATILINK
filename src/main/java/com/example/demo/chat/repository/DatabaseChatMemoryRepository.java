package com.example.demo.chat.repository;

import com.example.demo.chat.ChatMessage;
import com.example.demo.chat.entity.Conversation;
import com.example.demo.chat.entity.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class DatabaseChatMemoryRepository implements ChatMemoryRepository {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseChatMemoryRepository.class);
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    public DatabaseChatMemoryRepository(ConversationRepository conversationRepository, 
                                        MessageRepository messageRepository) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChatMessage> getMessages(String conversationId) {
        logger.info("Database getMessages - conversationId: {}", conversationId);
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
    public void saveMessages(String conversationId, List<ChatMessage> messages) {
        logger.info("Database saveMessages - conversationId: {}, messages count: {}", 
                    conversationId, messages.size());
        
        ensureConversationExists(conversationId);
        
        messageRepository.deleteByConversationId(conversationId);
        
        for (ChatMessage chatMessage : messages) {
            messageRepository.save(convertToMessage(conversationId, chatMessage));
        }
        
        logger.info("Database saveMessages - completed");
    }

    @Override
    @Transactional
    public void addMessage(String conversationId, ChatMessage message) {
        logger.info("Database addMessage - conversationId: {}, role: {}", conversationId, message.getRole());
        
        ensureConversationExists(conversationId);
        
        Message entity = convertToMessage(conversationId, message);
        messageRepository.save(entity);
        
        logger.info("Database addMessage - completed");
    }

    @Override
    @Transactional
    public void clear(String conversationId) {
        logger.info("Database clear - conversationId: {}", conversationId);
        messageRepository.deleteByConversationId(conversationId);
        logger.info("Database clear - completed");
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(String conversationId) {
        return conversationRepository.existsById(conversationId);
    }

    private void ensureConversationExists(String conversationId) {
        Optional<Conversation> existing = conversationRepository.findById(conversationId);
        if (existing.isEmpty()) {
            conversationRepository.save(new Conversation(conversationId));
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
                timestamp
        );
    }
}