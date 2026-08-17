package com.example.demo.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.LlmService;
import com.example.demo.weather.service.WeatherService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ToolCallingService {

    private static final int MAX_ITERATIONS = 5;

    private final LlmService llmService;
    private final SpringAiTools springAiTools;
    private final WeatherService weatherService;
    private final JdbcTemplate jdbc;
    private final Map<String, ToolInfo> toolRegistry = new LinkedHashMap<>();
    private final ExecutorService toolExecutor;

    @Autowired
    public ToolCallingService(LlmService llmService, SpringAiTools springAiTools,
                              WeatherService weatherService, JdbcTemplate jdbc) {
        this.llmService = llmService;
        this.springAiTools = springAiTools;
        this.weatherService = weatherService;
        this.jdbc = jdbc;
        this.toolExecutor = Executors.newFixedThreadPool(
                Math.min(Runtime.getRuntime().availableProcessors(), 4),
                r -> {
                    Thread t = new Thread(r, "tool-executor");
                    t.setDaemon(true);
                    return t;
                });
        registerTools(springAiTools);
        registerTools(weatherService);
        log.info("ToolCallingService initialized, registered {} tools: {}",
                toolRegistry.size(), toolRegistry.keySet());
    }

    private void registerTools(Object toolObject) {
        for (Method method : toolObject.getClass().getDeclaredMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation != null) {
                String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
                toolRegistry.put(toolName, new ToolInfo(toolObject, method, toolAnnotation));
                log.info("Registered tool: {}", toolName);
            }
        }
    }

    public Set<String> getRegisteredToolNames() {
        return new HashSet<>(toolRegistry.keySet());
    }

    public JSONArray buildToolsSchema() {
        return buildToolsSchema(null);
    }

    public JSONArray buildToolsSchema(Set<String> allowedToolNames) {
        JSONArray tools = new JSONArray();

        for (Map.Entry<String, ToolInfo> entry : toolRegistry.entrySet()) {
            String toolName = entry.getKey();

            if (allowedToolNames != null && !allowedToolNames.isEmpty()
                    && !allowedToolNames.contains(toolName)) {
                continue;
            }

            ToolInfo toolInfo = entry.getValue();

            JSONObject tool = new JSONObject();
            tool.put("type", "function");

            JSONObject function = new JSONObject();
            function.put("name", toolName);
            function.put("description", toolInfo.annotation.description().isEmpty()
                    ? toolName : toolInfo.annotation.description());

            JSONObject parameters = buildParametersSchema(toolInfo.method);
            function.put("parameters", parameters);

            tool.put("function", function);
            tools.add(tool);
        }

        log.info("Built tools schema with {} tools", tools.size());
        return tools;
    }

    private JSONObject buildParametersSchema(Method method) {
        JSONObject parameters = new JSONObject();
        parameters.put("type", "object");

        JSONObject properties = new JSONObject();
        JSONArray required = new JSONArray();

        Parameter[] params = method.getParameters();
        for (Parameter param : params) {
            String paramName = param.getName();

            JSONObject paramSchema = new JSONObject();
            paramSchema.put("type", getJsonType(param.getType()));

            ToolParam paramAnnotation = param.getAnnotation(ToolParam.class);
            if (paramAnnotation != null && !paramAnnotation.description().isEmpty()) {
                paramSchema.put("description", paramAnnotation.description());
            } else {
                paramSchema.put("description", "Parameter: " + paramName);
            }

            properties.put(paramName, paramSchema);

            if (paramAnnotation == null || paramAnnotation.required()) {
                required.add(paramName);
            }
        }

        parameters.put("properties", properties);
        if (!required.isEmpty()) {
            parameters.put("required", required);
        }

        return parameters;
    }

    private String getJsonType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class) return "integer";
        if (type == long.class || type == Long.class) return "integer";
        if (type == double.class || type == Double.class) return "number";
        if (type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type.isArray() || List.class.isAssignableFrom(type)) return "array";
        return "object";
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

        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JSONObject systemMsg = new JSONObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);
        }

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
                                        traceId, tc.toolName, tc.arguments);
                                result = executeTool(tc.toolName, tc.arguments);
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
                                generatedFiles.addAll(extractGeneratedFiles(result));
                            }

                            return callResult;
                        }, toolExecutor));
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
                        log.warn("Failed to parse tool arguments: {}", argsStr);
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

    public String executeTool(String toolName, JSONObject arguments) {
        ToolInfo toolInfo = toolRegistry.get(toolName);
        if (toolInfo == null) {
            log.warn("Unknown tool: {}", toolName);
            return "错误：未知工具 " + toolName;
        }

        try {
            java.lang.reflect.Parameter[] paramTypes = toolInfo.method.getParameters();
            Object[] args = new Object[paramTypes.length];

            for (int i = 0; i < paramTypes.length; i++) {
                String paramName = paramTypes[i].getName();
                Class<?> paramType = paramTypes[i].getType();

                if (arguments != null && arguments.containsKey(paramName)) {
                    args[i] = convertArgument(arguments.get(paramName), paramType);
                } else {
                    args[i] = getDefaultValue(paramType);
                }
            }

            toolInfo.method.setAccessible(true);
            Object result = toolInfo.method.invoke(toolInfo.target, args);

            return convertResult(result);

        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Tool execution failed: {}", toolName, cause);
            throw new RuntimeException(cause.getMessage(), cause);
        }
    }

    private Object convertArgument(Object value, Class<?> targetType) {
        if (value == null) return getDefaultValue(targetType);

        if (targetType == String.class) {
            return value.toString();
        } else if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(value.toString());
        } else if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(value.toString());
        } else if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(value.toString());
        } else if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(value.toString());
        }

        return value;
    }

    private Object getDefaultValue(Class<?> type) {
        if (type == String.class) return "";
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == double.class || type == Double.class) return 0.0;
        if (type == boolean.class || type == Boolean.class) return false;
        return null;
    }

    private String convertResult(Object result) {
        if (result == null) return "";
        if (result instanceof String) return (String) result;
        return JSON.toJSONString(result);
    }

    private List<Path> extractGeneratedFiles(String result) {
        List<Path> files = new ArrayList<>();
        if (result == null) return files;

        try {
            if (result.startsWith("[IMAGE:") && result.contains("]")) {
                int end = result.indexOf("]");
                String path = result.substring(7, end);
                files.add(Path.of(path));
                log.info("Extracted image file: {}", path);
            }
            if (result.startsWith("[AUDIO:") && result.contains("]")) {
                int end = result.indexOf("]");
                String path = result.substring(7, end);
                files.add(Path.of(path));
                log.info("Extracted audio file: {}", path);
            }
        } catch (Exception e) {
            log.warn("Failed to extract generated files from result");
        }

        return files;
    }

    public List<String> validateToolNames(List<String> requestedTools) {
        if (requestedTools == null || requestedTools.isEmpty()) {
            return new ArrayList<>(toolRegistry.keySet());
        }

        Set<String> registeredTools = toolRegistry.keySet();
        List<String> validTools = requestedTools.stream()
                .filter(registeredTools::contains)
                .collect(Collectors.toList());

        List<String> invalidTools = requestedTools.stream()
                .filter(t -> !registeredTools.contains(t))
                .collect(Collectors.toList());

        if (!invalidTools.isEmpty()) {
            log.warn("Invalid tool names filtered out: {}. Valid tools: {}",
                    invalidTools, validTools);
        }

        return validTools;
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
        toolExecutor.shutdown();
        try {
            if (!toolExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                toolExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            toolExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static class ToolInfo {
        final Object target;
        final Method method;
        final Tool annotation;

        ToolInfo(Object target, Method method, Tool annotation) {
            this.target = target;
            this.method = method;
            this.annotation = annotation;
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
