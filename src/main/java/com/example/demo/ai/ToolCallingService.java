package com.example.demo.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.LlmService;
import com.example.demo.monitor.MetricsService;
import com.example.demo.weather.service.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ToolCallingService {

    private static final int MAX_ITERATIONS = 5;

    private final LlmService llmService;
    private final JdbcTemplate jdbc;
    private final ToolRegistry registry;
    private final ExecutorService toolPool;
    private final ToolExecutor toolExecutorService;

    /** 可选：没有 Micrometer 的场景（部分单测）保持为空。 */
    @Autowired(required = false)
    public void setMetricsService(MetricsService metricsService) {
        this.toolExecutorService.setMetricsService(metricsService);
    }

    @Autowired
    public ToolCallingService(LlmService llmService, SpringAiTools springAiTools,
                              WeatherService weatherService, JdbcTemplate jdbc) {
        this.llmService = llmService;
        this.jdbc = jdbc;
        this.registry = new ToolRegistry(springAiTools, weatherService);
        this.toolExecutorService = new ToolExecutor(registry);
        this.toolPool = Executors.newFixedThreadPool(
                Math.min(Runtime.getRuntime().availableProcessors(), 4),
                r -> {
                    Thread t = new Thread(r, "tool-executor");
                    t.setDaemon(true);
                    return t;
                });
    }


    public Set<String> getRegisteredToolNames() {
        return registry.names();
    }

    public JSONArray buildToolsSchema() {
        return registry.buildSchema();
    }

    public JSONArray buildToolsSchema(Set<String> allowedToolNames) {
        return registry.buildSchema(allowedToolNames);
    }

    public ToolCallResponse chatWithTools(String systemPrompt, String userMessage) {
        return chatWithTools(null, systemPrompt, userMessage, null);
    }

    public ToolCallResponse chatWithTools(String systemPrompt, String userMessage,
                                           Set<String> allowedToolNames) {
        return chatWithTools(null, systemPrompt, userMessage, allowedToolNames);
    }

    public ToolCallResponse chatWithTools(String userId, String systemPrompt, String userMessage,
                                           Set<String> allowedToolNames) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.info("[Trace:{}] Tool chat started, message length: {}",
                traceId, userMessage.length());

        JSONArray messages = new JSONArray();

        String effectiveSystemPrompt = (systemPrompt != null && !systemPrompt.isEmpty())
                ? systemPrompt
                : ToolPromptBuilder.build(allowedToolNames, registry.names());
        JSONObject systemMsg = new JSONObject();
        systemMsg.put("role", "system");
        systemMsg.put("content", effectiveSystemPrompt);
        messages.add(systemMsg);

        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        userMsg.put("content", userMessage);
        messages.add(userMsg);

        return executeToolLoop(userId, messages, allowedToolNames, traceId);
    }

    private ToolCallResponse executeToolLoop(String userId, JSONArray messages, Set<String> allowedToolNames,
                                              String traceId) {
        JSONArray tools = buildToolsSchema(allowedToolNames);
        List<ToolCallResult> toolCallHistory = new ArrayList<>();
        List<Path> generatedFiles = new ArrayList<>();
        StringBuilder accumulatedText = new StringBuilder();
        long totalTokens = 0;
        int iterations = 0;

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            iterations = iteration + 1;
            log.info("[Trace:{}] Iteration {}/{}, messages: {}",
                    traceId, iterations, MAX_ITERATIONS, messages.size());

            try {
                JSONObject response = llmService.chatWithTools(messages, tools);

                long iterationTokens = extractTokens(response);
                totalTokens += iterationTokens;
                log.info("[Trace:{}] Tokens used this iteration: {}, total: {}",
                        traceId, iterationTokens, totalTokens);

                if (hasToolCalls(response)) {
                    JSONObject assistantMessage = getAssistantMessage(response);
                    if (assistantMessage != null) {
                        messages.add(assistantMessage);
                    }

                    List<ToolCallInfo> toolCalls = parseToolCalls(response);
                    log.info("[Trace:{}] Found {} tool calls, executing concurrently",
                            traceId, toolCalls.size());

                    Map<String, Future<ToolCallResult>> futureMap = new LinkedHashMap<>();

                    for (ToolCallInfo tc : toolCalls) {
                        futureMap.put(tc.id, CompletableFuture.supplyAsync(() -> {
                            long start = System.currentTimeMillis();
                            String result;
                            boolean success = true;
                            String errorMsg = null;

                            // 工具在独立线程执行，ThreadLocal 不会从请求线程传过来，
                            // 必须在工具线程内显式设置用户上下文，finally 清理防止串号。
                            String effectiveUserId = userId != null ? userId : UserContextHolder.getUserId();
                            UserContextHolder.setUserId(effectiveUserId);
                            try {
                                log.info("[Trace:{}] Executing tool: {} with args: {}",
                                        traceId, tc.toolName,
                                        com.example.demo.core.LogSanitizer.describeArgs(
                                                tc.arguments == null ? null : tc.arguments.toJSONString()));
                                result = toolExecutorService.executeTool(tc.toolName, tc.arguments);
                                log.info("[Trace:{}] Tool {} completed in {}ms",
                                        traceId, tc.toolName, System.currentTimeMillis() - start);
                            } catch (Exception e) {
                                success = false;
                                errorMsg = e.getMessage();
                                result = "工具执行异常：" + e.getMessage();
                                log.error("[Trace:{}] Tool {} failed: {}",
                                        traceId, tc.toolName, e.getMessage());
                            } finally {
                                UserContextHolder.clear();
                            }

                            long duration = System.currentTimeMillis() - start;
                            logToolCall(effectiveUserId, tc.toolName, success, duration, errorMsg);
                            ToolCallResult callResult = success
                                    ? ToolCallResult.success(traceId, tc.toolName, tc.arguments, result, duration)
                                    : ToolCallResult.error(traceId, tc.toolName, tc.arguments, errorMsg, duration);

                            synchronized (generatedFiles) {
                                generatedFiles.addAll(toolExecutorService.extractGeneratedFiles(result));
                            }

                            return callResult;
                        }, toolPool));
                    }

                    for (Map.Entry<String, Future<ToolCallResult>> entry : futureMap.entrySet()) {
                        String toolCallId = entry.getKey();
                        ToolCallResult callResult = entry.getValue().get(10, TimeUnit.SECONDS);
                        toolCallHistory.add(callResult);

                        JSONObject toolMsg = new JSONObject();
                        toolMsg.put("role", "tool");
                        toolMsg.put("tool_call_id", toolCallId);
                        toolMsg.put("content", callResult.getResult());
                        messages.add(toolMsg);
                    }

                } else {
                    String content = getTextContent(response);
                    if (content != null && !content.isBlank()) {
                        if (accumulatedText.length() == 0) {
                            accumulatedText.append(content);
                        } else if (!accumulatedText.toString().equals(content)) {
                            accumulatedText.append("\n").append(content);
                        }
                    }

                    String finalText = accumulatedText.length() > 0
                            ? accumulatedText.toString()
                            : "抱歉，我无法处理您的请求。";

                    log.info("[Trace:{}] Final response after {} iterations, {} tool calls, {} total tokens",
                            traceId, iterations, toolCallHistory.size(), totalTokens);

                    return ToolCallResponse.builder()
                            .text(finalText)
                            .generatedFiles(generatedFiles)
                            .toolCallHistory(toolCallHistory)
                            .totalIterations(iterations)
                            .totalTokens(totalTokens)
                            .traceId(traceId)
                            .build();
                }

            } catch (Exception e) {
                log.error("[Trace:{}] Tool calling iteration {} failed: {}",
                        traceId, iteration, e.getMessage());

                if (iteration == MAX_ITERATIONS - 1) {
                    return ToolCallResponse.builder()
                            .text("处理请求时发生错误: " + e.getMessage())
                            .generatedFiles(generatedFiles)
                            .toolCallHistory(toolCallHistory)
                            .totalIterations(iterations)
                            .totalTokens(totalTokens)
                            .traceId(traceId)
                            .build();
                }

                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        log.warn("[Trace:{}] Reached max iterations ({}), making final LLM fallback request",
                traceId, MAX_ITERATIONS);

        String fallbackText = accumulatedText.length() > 0
                ? accumulatedText.toString()
                : "处理请求超时，请稍后重试";

        try {
            JSONObject lastResponse = llmService.chatWithTools(messages, new JSONArray());
            long fallbackTokens = extractTokens(lastResponse);
            totalTokens += fallbackTokens;

            String lastContent = getTextContent(lastResponse);
            if (lastContent != null && !lastContent.isBlank()) {
                if (!fallbackText.equals(lastContent)) {
                    fallbackText = fallbackText + "\n" + lastContent;
                }
            }
            log.info("[Trace:{}] Final fallback completed, extra tokens: {}",
                    traceId, fallbackTokens);
        } catch (Exception e) {
            log.error("[Trace:{}] Final fallback request failed, using accumulated text",
                    traceId, e.getMessage());
        }

        return ToolCallResponse.builder()
                .text(fallbackText)
                .generatedFiles(generatedFiles)
                .toolCallHistory(toolCallHistory)
                .totalIterations(iterations + 1)
                .totalTokens(totalTokens)
                .traceId(traceId)
                .build();
    }

    private long extractTokens(JSONObject response) {
        try {
            JSONObject usage = response.getJSONObject("usage");
            if (usage != null) {
                long promptTokens = usage.getLongValue("prompt_tokens");
                long completionTokens = usage.getLongValue("completion_tokens");
                return promptTokens + completionTokens;
            }
        } catch (Exception e) {
            log.debug("Failed to extract tokens from response");
        }
        return 0;
    }

    private boolean hasToolCalls(JSONObject response) {
        try {
            JSONArray choices = response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return false;

            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            if (message == null) return false;

            JSONArray toolCalls = message.getJSONArray("tool_calls");
            return toolCalls != null && !toolCalls.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private JSONObject getAssistantMessage(JSONObject response) {
        try {
            JSONArray choices = response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return null;

            return choices.getJSONObject(0).getJSONObject("message");
        } catch (Exception e) {
            return null;
        }
    }

    private List<ToolCallInfo> parseToolCalls(JSONObject response) {
        List<ToolCallInfo> result = new ArrayList<>();

        try {
            JSONArray choices = response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return result;

            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            if (message == null) return result;

            JSONArray toolCalls = message.getJSONArray("tool_calls");
            if (toolCalls == null) return result;

            for (int i = 0; i < toolCalls.size(); i++) {
                JSONObject tc = toolCalls.getJSONObject(i);
                String id = tc.getString("id");

                JSONObject function = tc.getJSONObject("function");
                String name = function.getString("name");
                String argsStr = function.getString("arguments");

                JSONObject args = new JSONObject();
                if (argsStr != null && !argsStr.isEmpty()) {
                    try {
                        args = JSON.parseObject(argsStr);
                    } catch (Exception e) {
                        log.warn("Failed to parse tool arguments: {}", com.example.demo.core.LogSanitizer.describeArgs(argsStr));
                    }
                }

                result.add(new ToolCallInfo(id, name, args));
            }
        } catch (Exception e) {
            log.error("Failed to parse tool calls", e);
        }

        return result;
    }

    private String getTextContent(JSONObject response) {
        try {
            JSONArray choices = response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return null;

            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            if (message == null) return null;

            return message.getString("content");
        } catch (Exception e) {
            return null;
        }
    }


    public List<String> validateToolNames(List<String> requestedTools) {
        return registry.validateRequested(requestedTools);
    }

    /**
     * 工具调用日志落库（供数据观测/统计面板使用），失败仅告警不阻断主流程。
     */
    private void logToolCall(String userId, String toolName, boolean success, long duration, String errorMsg) {
        try {
            String trimmedError = errorMsg;
            if (trimmedError != null && trimmedError.length() > 500) {
                trimmedError = trimmedError.substring(0, 500);
            }
            jdbc.update("""
                    INSERT INTO tool_call_logs(user_id, tool_name, success, duration_ms, error_msg)
                    VALUES(?,?,?,?,?)
                    """, userId, toolName, success ? 1 : 0, duration, trimmedError);
        } catch (Exception e) {
            log.warn("[ToolLog] Failed to persist tool call log (tool={}): {}", toolName, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        toolPool.shutdown();
        try {
            if (!toolPool.awaitTermination(5, TimeUnit.SECONDS)) {
                toolPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            toolPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }


    private static class ToolCallInfo {
        final String id;
        final String toolName;
        final JSONObject arguments;

        ToolCallInfo(String id, String toolName, JSONObject arguments) {
            this.id = id;
            this.toolName = toolName;
            this.arguments = arguments;
        }
    }
}
