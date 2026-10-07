package com.example.demo.chat;

import com.example.demo.chat.event.SummaryUpdateEvent;
import com.example.demo.chat.event.VectorSaveEvent;
import com.example.demo.chat.repository.ChatMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
public class ChatMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryService.class);

    private static final String SYSTEM_ROLE = "system";
    private static final String USER_ROLE = "user";
    private static final String ASSISTANT_ROLE = "assistant";
    private static final String SUMMARY_ROLE = "system";
    private static final String SUMMARY_PREFIX = "【对话摘要】";
    private static final String RAG_CONTEXT_PREFIX = "参考以下历史对话信息，帮助回答用户当前问题：";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final ChatMemoryRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final UserSessionService userSessionService;
    
    @Autowired
    @Lazy
    private LlmService llmService;
    
    @Autowired
    @Lazy
    private VectorStoreService vectorStoreService;

    @Value("${chat.memory.max-messages:10}")
    private int maxMessages;

    @Value("${chat.memory.max-tokens:2000}")
    private long maxTokens;

    @Value("${chat.memory.summary-threshold:3000}")
    private long summaryThreshold;

    @Value("${chat.memory.summary-keep-recent:5}")
    private int summaryKeepRecent;

    public ChatMemoryService(ChatMemoryRepository repository,
                             ApplicationEventPublisher eventPublisher,
                             UserSessionService userSessionService) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.userSessionService = userSessionService;
    }

    public List<ChatMessage> getConversationHistory(String userId, String conversationId) {
        return repository.getMessages(userId, conversationId);
    }

    public List<ChatMessage> buildPromptMessages(String userId, String conversationId,
                                                 String systemPrompt, String userMessage) {
        return buildPromptMessages(userId, conversationId, systemPrompt, null, userMessage);
    }

    public List<ChatMessage> buildPromptMessages(String userId, String conversationId,
                                                 String systemPrompt, String ragContext,
                                                 String userMessage) {
        List<ChatMessage> history = repository.getMessages(userId, conversationId);
        List<ChatMessage> promptMessages = new ArrayList<>();
        String summary = null;

        for (ChatMessage msg : history) {
            if (!SYSTEM_ROLE.equals(msg.getRole())) {
                promptMessages.add(msg);
            } else if (isSummaryMessage(msg)) {
                // 持久化摘要只作为历史事实补充，旧系统消息不再逐条透传。
                summary = msg.getContent();
            }
        }

        ChatMessage systemMessage = buildSystemMessage(systemPrompt, ragContext, summary);
        if (systemMessage != null) {
            promptMessages.add(0, systemMessage);
        }
        promptMessages.add(new ChatMessage(USER_ROLE, userMessage));

        return truncateMessages(promptMessages);
    }

    private List<ChatMessage> truncateMessages(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>();
        ChatMessage systemMessage = null;
        for (ChatMessage message : messages) {
            if (SYSTEM_ROLE.equals(message.getRole())) {
                if (systemMessage == null) {
                    systemMessage = message;
                }
            } else {
                result.add(message);
            }
        }

        long totalTokens = result.stream().mapToLong(ChatMessage::getTokenCount).sum();
        if (systemMessage != null) {
            totalTokens += systemMessage.getTokenCount();
        }

        if (totalTokens > summaryThreshold
                && result.size() > summaryKeepRecent * 2
                && !containsSummary(systemMessage)) {
            logger.info("Total tokens {} exceeds summary threshold {}, generating rolling summary", totalTokens, summaryThreshold);
            String summary = generateRollingSummaryContent(result);
            if (summary != null && !summary.isEmpty()) {
                String summaryMessage = SUMMARY_PREFIX + summary;
                String baseSystemPrompt = systemMessage != null ? systemMessage.getContent() : null;
                systemMessage = buildSystemMessage(baseSystemPrompt, null, summaryMessage);
            }
            totalTokens = result.stream().mapToLong(ChatMessage::getTokenCount).sum();
            if (systemMessage != null) {
                totalTokens += systemMessage.getTokenCount();
            }
        }

        while (result.size() > maxMessages && result.size() > 1) {
            totalTokens -= result.remove(0).getTokenCount();
        }

        while (totalTokens > maxTokens && result.size() > 1) {
            totalTokens -= result.remove(0).getTokenCount();
        }

        List<ChatMessage> finalMessages = new ArrayList<>();
        if (systemMessage != null) {
            finalMessages.add(systemMessage);
        }
        finalMessages.addAll(result);

        logger.debug("Truncated messages from {} to {}, total tokens: {}",
                messages.size(), finalMessages.size(), totalTokens);
        return finalMessages;
    }

    private ChatMessage buildSystemMessage(String systemPrompt, String ragContext, String summary) {
        StringBuilder content = new StringBuilder();
        appendSystemSection(content, systemPrompt);
        if (ragContext != null && !ragContext.isBlank()) {
            appendSystemSection(content, RAG_CONTEXT_PREFIX + "\n" + ragContext.trim());
        }
        appendSystemSection(content, summary);
        if (content.length() == 0) {
            return null;
        }
        return new ChatMessage(SYSTEM_ROLE, content.toString());
    }

    private void appendSystemSection(StringBuilder content, String section) {
        if (section == null || section.isBlank()) {
            return;
        }
        if (content.length() > 0) {
            content.append("\n\n");
        }
        content.append(section.trim());
    }

    private boolean containsSummary(ChatMessage systemMessage) {
        return systemMessage != null
                && systemMessage.getContent() != null
                && systemMessage.getContent().contains(SUMMARY_PREFIX);
    }

    private boolean isSummaryMessage(ChatMessage message) {
        return SYSTEM_ROLE.equals(message.getRole())
                && message.getContent() != null
                && message.getContent().startsWith(SUMMARY_PREFIX);
    }

    private String generateSummary(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (ChatMessage msg : messages) {
            String rolePrefix = USER_ROLE.equals(msg.getRole()) ? "用户: " : "助手: ";
            sb.append(rolePrefix).append(msg.getContent()).append("\n");
        }

        String prompt = "请将以下对话历史浓缩为一段简短的摘要，保留核心事实和用户意图：\n\n" + sb.toString();

        try {
            String summary = llmService.chat(prompt, "你是一个专业的对话摘要助手，请将对话内容压缩为简明扼要的摘要。");
            return summary.trim();
        } catch (IOException e) {
            logger.error("Failed to generate rolling summary", e);
            return "";
        }
    }

    public void saveMessagePair(String userId, String conversationId, String userMessage, String assistantReply) {
        repository.addMessage(userId, conversationId, new ChatMessage(USER_ROLE, userMessage));
        repository.addMessage(userId, conversationId, new ChatMessage(ASSISTANT_ROLE, assistantReply));
        logger.debug("Saved message pair for user: {}, conversation: {}", userId, conversationId);
        
        eventPublisher.publishEvent(new VectorSaveEvent(userId, conversationId, userMessage, assistantReply));
        eventPublisher.publishEvent(new SummaryUpdateEvent(userId, conversationId));
    }

    public void checkAndUpdateSummary(String userId, String conversationId) {
        List<ChatMessage> history = repository.getMessages(userId, conversationId);
        long totalTokens = history.stream().mapToLong(ChatMessage::getTokenCount).sum();
        
        if (totalTokens > summaryThreshold && history.size() > summaryKeepRecent * 2) {
            String summary = generateRollingSummaryContent(history);
            if (summary != null && !summary.isEmpty()) {
                // 生成摘要可能耗时较久，期间会话可能已被清空或新增消息。
                // 写入前重新校验历史，避免把过期摘要回写到已清空的会话。
                long snapshotCount = countConversationMessages(history);
                long currentCount = countConversationMessages(repository.getMessages(userId, conversationId));
                if (currentCount != snapshotCount) {
                    logger.info("Conversation changed or was cleared during summary generation, "
                            + "skipping stale summary write for conversation: {}", conversationId);
                    return;
                }
                repository.removeSystemMessages(userId, conversationId, SUMMARY_PREFIX);
                String timestamp = LocalDateTime.now().format(FORMATTER);
                String summaryContent = SUMMARY_PREFIX + "[更新时间: " + timestamp.substring(0, 16) + "]\n" + summary;
                repository.addMessage(userId, conversationId, new ChatMessage(SYSTEM_ROLE, summaryContent));
                logger.info("Summary saved for conversation: {}, length: {} chars", conversationId, summary.length());
            }
        }
    }

    private long countConversationMessages(List<ChatMessage> history) {
        return history.stream()
                .filter(message -> !SYSTEM_ROLE.equals(message.getRole()))
                .count();
    }

    private String generateRollingSummaryContent(List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatMessage msg : history) {
            if (!SYSTEM_ROLE.equals(msg.getRole())) {
                messages.add(msg);
            }
        }

        int keepRecentCount = summaryKeepRecent * 2;
        List<ChatMessage> oldMessages = new ArrayList<>();
        for (int i = 0; i < messages.size() - keepRecentCount; i++) {
            oldMessages.add(messages.get(i));
        }

        return generateSummary(oldMessages);
    }

    public void clearConversation(String userId, String conversationId) {
        requireIdentifier(userId, "userId");
        requireIdentifier(conversationId, "conversationId");
        repository.clear(userId, conversationId);
        vectorStoreService.clearConversationVectors(userId, conversationId);
        userSessionService.clearSession(userId);
        logger.info("Cleared conversation history, vectors and session state for user: {}, conversation: {}",
                userId, conversationId);
    }

    public void clearUserSession(String userId) {
        requireIdentifier(userId, "userId");
        userSessionService.clearSession(userId);
        logger.info("Cleared session state for user: {}", userId);
    }

    public boolean hasConversation(String userId, String conversationId) {
        return repository.exists(userId, conversationId);
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public void setMaxTokens(long maxTokens) {
        this.maxTokens = maxTokens;
    }

    private void requireIdentifier(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
