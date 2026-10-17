package com.example.demo.chat;

import com.example.demo.chat.entity.ConversationSummary;
import com.example.demo.chat.event.SummaryUpdateEvent;
import com.example.demo.chat.event.VectorSaveEvent;
import com.example.demo.chat.lifecycle.SummaryLifecycleService;
import com.example.demo.chat.repository.ChatMemoryRepository;
import com.example.demo.chat.repository.mysql.ConversationSummaryRepository;
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
    private static final String RAG_CONTEXT_PREFIX = "参考以下检索结果，帮助回答用户当前问题：";
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

    @Autowired(required = false)
    private ConversationSummaryRepository conversationSummaryRepository;

    @Autowired(required = false)
    private SummaryLifecycleService summaryLifecycleService;

    @Value("${app.message-lifecycle.enabled:false}")
    private boolean messageLifecycleEnabled = false;

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

    /**
     * 读取一页可回放的历史消息（仅 user/assistant）。
     * 没有当前会话时返回空页，不创建新会话。
     */
    public HistoryPage getHistory(String userId, String conversationId, Long beforeId, int limit) {
        if (conversationId == null || conversationId.isBlank()) {
            return new HistoryPage(null, List.of(), false, null);
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        return repository.getHistory(userId, conversationId, beforeId, limit);
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
        String summary = loadPersistedSummary(conversationId);

        for (ChatMessage msg : history) {
            if (!SYSTEM_ROLE.equals(msg.getRole())) {
                promptMessages.add(sanitizeCitationMarkers(msg));
            } else if (summary == null && isSummaryMessage(msg)) {
                // 新摘要在 conversation_summaries 中；没有行时回退到旧 system 镜像行。
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

    private String loadPersistedSummary(String conversationId) {
        if (conversationSummaryRepository == null || conversationId == null || conversationId.isBlank()) {
            return null;
        }
        try {
            return conversationSummaryRepository.findById(conversationId)
                    .map(this::formatStoredSummary)
                    .orElse(null);
        } catch (Exception exception) {
            logger.warn("Failed to load conversation summary for {}: {}",
                    conversationId, exception.getMessage());
            return null;
        }
    }

    private String formatStoredSummary(ConversationSummary summary) {
        if (summary == null || summary.getSummaryText() == null
                || summary.getSummaryText().isBlank()) {
            return null;
        }
        String updatedAt = summary.getUpdatedAt() == null
                ? ""
                : "[更新时间: " + summary.getUpdatedAt().format(FORMATTER).substring(0, 16) + "]\n";
        return SUMMARY_PREFIX + updatedAt + summary.getSummaryText().trim();
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
            String content = ASSISTANT_ROLE.equals(msg.getRole())
                    ? CitationMarkers.strip(msg.getContent())
                    : msg.getContent();
            sb.append(rolePrefix).append(content).append("\n");
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
        saveMessagePair(userId, conversationId, userMessage, assistantReply, List.of(), null);
    }

    /**
     * 保存一轮对话，并把本次回答的来源快照写到 assistant 消息上。
     * user 消息与摘要消息的 sources_json 始终为 NULL。
     */
    public void saveMessagePair(String userId, String conversationId, String userMessage,
                                String assistantReply,
                                List<RagContextService.SourceRef> sources, String traceId) {
        repository.addMessage(userId, conversationId, new ChatMessage(USER_ROLE, userMessage));
        String sourcesJson = SourceRefCodec.toJson(sources, traceId);
        repository.addMessage(userId, conversationId,
                new ChatMessage(ASSISTANT_ROLE, assistantReply), sourcesJson);
        logger.debug("Saved message pair for user: {}, conversation: {}, sources: {}",
                userId, conversationId, sources == null ? 0 : sources.size());

        // 引用标记只用于界面展示，不进入对话向量，避免 embedding 噪声。
        eventPublisher.publishEvent(new VectorSaveEvent(userId, conversationId, userMessage,
                CitationMarkers.strip(assistantReply)));
        eventPublisher.publishEvent(new SummaryUpdateEvent(userId, conversationId));
    }

    private ChatMessage sanitizeCitationMarkers(ChatMessage message) {
        if (message == null || !ASSISTANT_ROLE.equals(message.getRole())) {
            return message;
        }
        String stripped = CitationMarkers.strip(message.getContent());
        if (stripped == null || stripped.equals(message.getContent())) {
            return message;
        }
        return new ChatMessage(message.getRole(), stripped, message.getTimestamp());
    }

    public void checkAndUpdateSummary(String userId, String conversationId) {
        if (messageLifecycleEnabled) {
            if (summaryLifecycleService != null) {
                summaryLifecycleService.summarize(userId, conversationId);
            }
            return;
        }

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
