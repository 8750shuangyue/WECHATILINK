package com.example.wechat.bot.core.service;

import com.example.wechat.bot.common.config.ChatApiProperties;
import com.example.wechat.bot.common.config.WeatherProperties;
import com.example.wechat.bot.core.model.dto.ChatCompletionRequest;
import com.example.wechat.bot.core.model.dto.ChatCompletionResponse;
import com.example.wechat.bot.core.model.dto.ChatMessage;
import com.example.wechat.bot.core.model.dto.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ChatCompletionService {

    private static final Logger logger = LoggerFactory.getLogger(ChatCompletionService.class);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final ChatApiProperties properties;

    @Autowired
    private ChatApiStatsService statsService;

    @Autowired
    private ConversationMemoryService conversationMemoryService;

    @Autowired(required = false)
    private WeatherService weatherService;

    /**
     * 所有可用工具的注册表。后续加新工具只需在此添加 map entry。
     */
    private final List<ToolDefinition> toolDefinitions;

    public ChatCompletionService(ChatApiProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .build();

        // 注册 get_weather 工具
        this.toolDefinitions = List.of(
                ToolDefinition.of(
                        "get_weather",
                        "查询指定城市的实时天气信息。当用户询问天气、温度、降雨等天气相关问题时使用。城市名支持中文。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "city", Map.of(
                                                "type", "string",
                                                "description", "城市名，如北京、上海、广州"
                                        )
                                ),
                                "required", List.of("city")
                        )
                )
        );
    }

    // ========== 结构化对话（带记忆 + 格式路由 + Function Calling） ==========

    /**
     * 结构化对话（无工具版本）。内部委托给带工具版本，tools 传空。
     */
    public String structuredChat(String userId, String userMessage, String systemPrompt) {
        return structuredChat(userId, userMessage, systemPrompt, null);
    }

    /**
     * 结构化对话（带工具版本）。
     * 支持 Function Calling：AI 返回 tool_calls 时自动执行工具，
     * 结果回传给 AI 后再次生成回复，循环直到 AI 不再调用工具。
     */
    public String structuredChat(String userId, String userMessage, String systemPrompt, List<ToolDefinition> extraTools) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }

        String apiKey = properties.getKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("sk-demo-")) {
            statsService.recordFailure("chat.api.key 未配置或为占位 key");
            return null;
        }

        // 组装 messages：system prompt + 历史记忆 + 当前消息
        List<ChatMessage> history = conversationMemoryService.getHistory(userId);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", systemPrompt));
        messages.addAll(history);
        messages.add(new ChatMessage("user", userMessage));

        // 合并全局工具列表和本次额外工具
        List<ToolDefinition> tools = mergeTools(extraTools);

        try {
            String finalReply = chatWithToolLoop(messages, tools, apiKey);

            // API 调用成功后才写入记忆
            conversationMemoryService.addUserMessage(userId, userMessage);
            conversationMemoryService.addAssistantMessage(userId, finalReply);

            statsService.recordSuccess();
            return finalReply;
        } catch (Exception e) {
            statsService.recordFailure(e.getMessage());
            logger.error("结构化对话 API 调用失败", e);
            return null;
        }
    }

    /**
     * 工具循环：调 API → 检查 tool_calls → 执行工具 → 再调 API → 重复
     */
    private String chatWithToolLoop(List<ChatMessage> messages, List<ToolDefinition> tools, String apiKey) {
        int maxIterations = 5; // 防止无限循环

        for (int round = 0; round < maxIterations; round++) {
            ChatCompletionRequest request = new ChatCompletionRequest(
                    properties.getModel(), messages, properties.getTemperature(), tools
            );

            ChatCompletionResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        try {
                            byte[] body = res.getBody().readAllBytes();
                            String errorBody = new String(body, StandardCharsets.UTF_8);
                            throw new RuntimeException("API 返回错误 (HTTP " + res.getStatusCode() + "): " + errorBody);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException("读取 API 错误响应失败: " + e.getMessage(), e);
                        }
                    })
                    .body(ChatCompletionResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new IllegalStateException("API 未返回有效内容");
            }

            ChatCompletionResponse.ChatChoice choice = response.choices().get(0);
            ChatMessage responseMsg = choice.message();

            if (responseMsg == null) {
                throw new IllegalStateException("API 返回消息为空");
            }

            // 将 AI 回复加入消息列表（可能带 tool_calls）
            messages.add(responseMsg);
            List<ChatMessage.ToolCall> toolCalls = responseMsg.toolCalls();
            logger.info("API 返回: round={}, content={}, tool_calls={}, finish_reason={}",
                    round,
                    responseMsg.content() != null ? (responseMsg.content().length() > 80 ? responseMsg.content().substring(0, 80) + "..." : responseMsg.content()) : null,
                    toolCalls != null ? toolCalls.size() : 0,
                    choice.finishReason());

            // 检查是否有 tool_calls
            if (toolCalls == null || toolCalls.isEmpty()) {
                // AI 不再调工具 → 返回最终回复文本
                String reply = responseMsg.content();
                if (reply == null || reply.isBlank()) {
                    throw new IllegalStateException("API 返回消息内容为空");
                }
                return reply.trim();
            }

            // 执行每个工具调用，结果加入消息列表
            for (ChatMessage.ToolCall tc : toolCalls) {
                logger.info("执行工具调用: func={}, args={}", tc.function().name(), tc.function().arguments());
                String result = executeTool(tc);
                logger.info("工具返回: result={}", result.length() > 150 ? result.substring(0, 150) + "..." : result);
                messages.add(ChatMessage.toolResponse(tc.id(), result));
            }
        }

        throw new IllegalStateException("工具调用超出最大轮次 (" + maxIterations + ")，已终止");
    }

    /**
     * 执行工具调用，返回工具执行结果（JSON 字符串）
     */
    private String executeTool(ChatMessage.ToolCall toolCall) {
        String name = toolCall.function().name();
        String arguments = toolCall.function().arguments();

        try {
            switch (name) {
                case "get_weather" -> {
                    if (weatherService == null) {
                        logger.warn("WeatherService 未注入，天气查询不可用");
                        return "{\"error\": \"天气查询服务不可用\"}";
                    }
                    JsonNode args = JSON_MAPPER.readTree(arguments);
                    String city = args.path("city").asText("北京");
                    return weatherService.getWeather(city);
                }
                default -> {
                    logger.warn("未知工具调用: {}", name);
                    return "{\"error\": \"未知工具: " + name + "\"}";
                }
            }
        } catch (Exception e) {
            logger.error("工具执行失败: name={}, args={}", name, arguments, e);
            return "{\"error\": \"工具执行失败: " + e.getMessage() + "\"}";
        }
    }

    /**
     * 合并全局工具列表和本次额外工具。
     * 当前只有全局工具（get_weather），extraTools 暂时保留扩展点。
     */
    private List<ToolDefinition> mergeTools(List<ToolDefinition> extraTools) {
        if (extraTools == null || extraTools.isEmpty()) {
            return toolDefinitions.isEmpty() ? null : toolDefinitions;
        }
        List<ToolDefinition> merged = new ArrayList<>(toolDefinitions);
        merged.addAll(extraTools);
        return merged;
    }

    // ========== 简单对话（无工具、供测试接口使用） ==========

    public String chat(String userId, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "请发送文本内容。";
        }

        String apiKey = properties.getKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("sk-demo-")) {
            statsService.recordFailure("chat.api.key 未配置或为占位 key");
            return "当前尚未配置可用的 chat.api.key，请先在 application.properties 中替换为真实 API Key。";
        }

        List<ChatMessage> history = conversationMemoryService.getHistory(userId);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", properties.getSystemPrompt()));
        messages.addAll(history);
        messages.add(new ChatMessage("user", userMessage));

        ChatCompletionRequest request = new ChatCompletionRequest(
                properties.getModel(), messages, properties.getTemperature()
        );

        try {
            ChatCompletionResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        try {
                            byte[] body = res.getBody().readAllBytes();
                            String errorBody = new String(body, StandardCharsets.UTF_8);
                            throw new RuntimeException("API 返回错误 (HTTP " + res.getStatusCode() + "): " + errorBody);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException("读取 API 错误响应失败: " + e.getMessage(), e);
                        }
                    })
                    .body(ChatCompletionResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new IllegalStateException("chat API 未返回有效内容");
            }

            ChatCompletionResponse.ChatChoice choice = response.choices().get(0);
            ChatMessage message = choice.message();
            if (message == null || message.content() == null || message.content().isBlank()) {
                throw new IllegalStateException("chat API 返回消息为空");
            }

            String reply = message.content().trim();

            conversationMemoryService.addUserMessage(userId, userMessage);
            conversationMemoryService.addAssistantMessage(userId, reply);

            statsService.recordSuccess();
            return reply;
        } catch (Exception e) {
            statsService.recordFailure(e.getMessage());
            throw new RuntimeException("调用文本对话 API 失败: " + e.getMessage(), e);
        }
    }
}
