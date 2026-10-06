package com.example.demo.ai;

import com.example.demo.chat.VectorStoreService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
public class SpringAiChatService {

    private final ChatModel chatModel;
    private final VectorStoreService vectorStoreService;
    private final ToolCallingService toolCallingService;

    private static final String SYSTEM_PROMPT = """
        # Role & Core Objective
        你是一个具备多模态感知能力的智能助手。你的核心任务是精准识别用户的输入模态和真实意图，严格匹配并调用正确的工具。
        
        # 安全与隐私规则
        1. 绝对禁止输出任何本地文件路径、服务器路径、URL路径等敏感信息。
        2. 不要提及任何技术实现细节。
        
        # 对话记忆能力
        你拥有完整的对话历史记忆，能够记住之前的对话内容。
        
        # 意图识别优先规则
        1. 当用户发送图片后紧接着发送文本，应优先回答用户的文本问题。
        2. 如果用户发了图片但没说话，可以简短提示"图片收到了，需要我做什么？"。
        
        # 实时信息模态 - 天气查询
        触发 getWeather/queryWeather：
          用户指令：询问当前、未来、特定地点的天气、温度、空气质量等。
          禁忌：严禁凭记忆回答天气，必须调用工具获取实时数据。
        
        # 时间查询模态
        触发 getCurrentTime：
          用户指令：询问当前时间、日期等。
        
        # 搜索模态 - 联网搜索
        触发 webSearch：
          用户指令：涉及实时信息、时间敏感问题，或明确包含"搜索"、"查一下"等词汇。
        
        # 决策逻辑与兜底机制
        纯文本兜底：只有当用户的请求是通用知识问答、日常闲聊，且完全不涉及上述任何模态特征和工具触发条件时，才直接生成文本回答。
        """;

    @Autowired
    public SpringAiChatService(ChatModel chatModel, VectorStoreService vectorStoreService, 
                               ToolCallingService toolCallingService) {
        this.chatModel = chatModel;
        this.vectorStoreService = vectorStoreService;
        this.toolCallingService = toolCallingService;
        log.info("SpringAiChatService initialized with ToolCallingService");
    }

    public String chat(String userMessage) {
        return chat(userMessage, SYSTEM_PROMPT, null);
    }

    public String chat(String userMessage, String systemPrompt) {
        return chat(userMessage, systemPrompt, null);
    }

    public String chat(String userMessage, String systemPrompt, String conversationId) {
        log.info("Spring AI chat called, userMessage: {}, conversationId: {}", 
                userMessage.length() > 50 ? userMessage.substring(0, 50) + "..." : userMessage, conversationId);

        List<Message> messages = new ArrayList<>();

        String effectiveSystemPrompt = systemPrompt != null ? systemPrompt : SYSTEM_PROMPT;

        if (conversationId != null && vectorStoreService != null) {
            try {
                List<String> ragResults = vectorStoreService.searchSimilar(userMessage, conversationId);
                if (!ragResults.isEmpty()) {
                    StringBuilder ragContext = new StringBuilder();
                    ragContext.append("\n\n# RAG知识库检索结果\n");
                    ragContext.append("根据您的提问，从知识库中检索到以下相关信息，供您参考：\n");
                    for (int i = 0; i < ragResults.size(); i++) {
                        ragContext.append(String.format("[%d] %s\n", i + 1, ragResults.get(i)));
                    }
                    ragContext.append("\n请根据以上检索结果回答用户问题。");
                    effectiveSystemPrompt += ragContext.toString();
                    log.info("Added {} RAG search results to system prompt", ragResults.size());
                }
            } catch (Exception e) {
                log.warn("Failed to retrieve RAG context: {}", e.getMessage());
            }
        }

        messages.add(new SystemMessage(effectiveSystemPrompt));
        messages.add(new UserMessage(userMessage));

        Prompt prompt = new Prompt(messages);
        
        try {
            ChatResponse response = chatModel.call(prompt);
            Object output = response.getResult().getOutput();
            String content = output != null ? output.toString() : null;
            log.info("Spring AI chat completed, response length: {}", content != null ? content.length() : 0);
            return content;
        } catch (Exception e) {
            log.error("Spring AI chat failed", e);
            throw new RuntimeException("AI服务调用失败: " + e.getMessage(), e);
        }
    }

    public String chatWithTools(String userMessage) {
        return chatWithTools(userMessage, SYSTEM_PROMPT, null).getText();
    }

    public String chatWithTools(String userMessage, String systemPrompt) {
        return chatWithTools(userMessage, systemPrompt, null).getText();
    }

    public ToolCallResponse chatWithToolsFull(String userMessage) {
        return chatWithTools(userMessage, SYSTEM_PROMPT, null);
    }

    public ToolCallResponse chatWithTools(String userMessage, String systemPrompt,
                                           Set<String> allowedToolNames) {
        return chatWithTools(null, userMessage, systemPrompt, allowedToolNames);
    }

    public ToolCallResponse chatWithTools(String userId, String userMessage, String systemPrompt,
                                           Set<String> allowedToolNames) {
        log.info("Spring AI chat with tools called, userMessage: {}, allowedTools: {}",
                userMessage.length() > 50 ? userMessage.substring(0, 50) + "..." : userMessage,
                allowedToolNames);
        return toolCallingService.chatWithTools(userId, systemPrompt, userMessage, allowedToolNames);
    }

    public Flux<String> chatWithToolsStream(String userMessage) {
        log.info("Spring AI chat with tools stream called (placeholder)");
        return Flux.just("Stream mode not implemented yet. Use non-streaming mode.");
    }

    public String chatWithTemplate(String template, Map<String, Object> variables) {
        PromptTemplate promptTemplate = new PromptTemplate(template);
        Prompt prompt = promptTemplate.create(variables);
        
        try {
            ChatResponse response = chatModel.call(prompt);
            Object output = response.getResult().getOutput();
            return output != null ? output.toString() : null;
        } catch (Exception e) {
            log.error("Spring AI chat with template failed", e);
            throw new RuntimeException("AI服务调用失败: " + e.getMessage(), e);
        }
    }
}
