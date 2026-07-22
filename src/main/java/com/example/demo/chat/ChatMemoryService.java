package com.example.demo.chat;

import com.example.demo.chat.repository.ChatMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
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

    private final ChatMemoryRepository repository;
    
    @Autowired
    @Lazy
    private LlmService llmService;

    @Value("${chat.memory.max-messages:10}")
    private int maxMessages;

    @Value("${chat.memory.max-tokens:2000}")
    private long maxTokens;

    @Value("${chat.memory.summary-threshold:3000}")
    private long summaryThreshold;

    @Value("${chat.memory.summary-keep-recent:5}")
    private int summaryKeepRecent;

    public ChatMemoryService(ChatMemoryRepository repository) {
        this.repository = repository;
    }

    public List<ChatMessage> getConversationHistory(String conversationId) {
        return repository.getMessages(conversationId);
    }

    public List<ChatMessage> buildPromptMessages(String conversationId, String systemPrompt, String userMessage) {
        List<ChatMessage> history = repository.getMessages(conversationId);
        List<ChatMessage> promptMessages = new ArrayList<>();

        boolean hasSystemPrompt = false;
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            promptMessages.add(new ChatMessage(SYSTEM_ROLE, systemPrompt));
            hasSystemPrompt = true;
        }

        for (ChatMessage msg : history) {
            if (!SYSTEM_ROLE.equals(msg.getRole())) {
                promptMessages.add(msg);
            } else if (!hasSystemPrompt) {
                promptMessages.add(msg);
                hasSystemPrompt = true;
            }
        }

        promptMessages.add(new ChatMessage(USER_ROLE, userMessage));

        return truncateMessages(promptMessages);
    }

    private List<ChatMessage> truncateMessages(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }

        List<ChatMessage> result = new ArrayList<>(messages);

        ChatMessage systemMessage = null;
        int systemIndex = -1;
        for (int i = 0; i < result.size(); i++) {
            if (SYSTEM_ROLE.equals(result.get(i).getRole()) && !result.get(i).getContent().startsWith(SUMMARY_PREFIX)) {
                systemMessage = result.get(i);
                systemIndex = i;
                break;
            }
        }

        if (systemMessage != null) {
            result.remove(systemIndex);
        }

        long totalTokens = result.stream().mapToLong(ChatMessage::getTokenCount).sum();
        if (systemMessage != null) {
            totalTokens += systemMessage.getTokenCount();
        }

        if (totalTokens > summaryThreshold && result.size() > summaryKeepRecent * 2) {
            logger.info("Total tokens {} exceeds summary threshold {}, generating rolling summary", totalTokens, summaryThreshold);
            result = generateRollingSummary(result, systemMessage);
            totalTokens = result.stream().mapToLong(ChatMessage::getTokenCount).sum();
            if (systemMessage != null) {
                totalTokens += systemMessage.getTokenCount();
            }
        }

        while (result.size() > maxMessages) {
            result.remove(0);
        }

        while (totalTokens > maxTokens && result.size() > 0) {
            totalTokens -= result.remove(0).getTokenCount();
        }

        if (systemMessage != null) {
            result.add(0, systemMessage);
        }

        logger.debug("Truncated messages from {} to {}, total tokens: {}", messages.size(), result.size(), totalTokens);
        return result;
    }

    private List<ChatMessage> generateRollingSummary(List<ChatMessage> messages, ChatMessage systemMessage) {
        List<ChatMessage> result = new ArrayList<>();

        int keepRecentCount = summaryKeepRecent * 2;
        List<ChatMessage> recentMessages = new ArrayList<>();
        List<ChatMessage> oldMessages = new ArrayList<>();

        for (int i = 0; i < messages.size(); i++) {
            if (i >= messages.size() - keepRecentCount) {
                recentMessages.add(messages.get(i));
            } else {
                oldMessages.add(messages.get(i));
            }
        }

        logger.info("Splitting messages: {} old messages, {} recent messages", oldMessages.size(), recentMessages.size());

        String summary = generateSummary(oldMessages);

        if (summary != null && !summary.isEmpty()) {
            ChatMessage summaryMessage = new ChatMessage(SUMMARY_ROLE, SUMMARY_PREFIX + summary);
            result.add(summaryMessage);
            logger.info("Generated rolling summary, length: {} chars", summary.length());
        }

        result.addAll(recentMessages);

        return result;
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

    public void saveMessagePair(String conversationId, String userMessage, String assistantReply) {
        repository.addMessage(conversationId, new ChatMessage(USER_ROLE, userMessage));
        repository.addMessage(conversationId, new ChatMessage(ASSISTANT_ROLE, assistantReply));
        logger.debug("Saved message pair for conversation: {}", conversationId);
    }

    public void clearConversation(String conversationId) {
        repository.clear(conversationId);
        logger.info("Cleared conversation history for: {}", conversationId);
    }

    public boolean hasConversation(String conversationId) {
        return repository.exists(conversationId);
    }

    public void setMaxMessages(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public void setMaxTokens(long maxTokens) {
        this.maxTokens = maxTokens;
    }
}