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

    /**
     * 工具按功能分组（用于构建默认系统提示词）。
     * 提示词会按实际注册的工具与 allowedTools 做过滤，避免引用不存在的工具。
     */
    private static final Map<String, List<String>> TOOL_GROUPS = new LinkedHashMap<>();
    private static final Map<String, String> GROUP_GUIDANCE = new LinkedHashMap<>();

    static {
        TOOL_GROUPS.put("实时天气", List.of("getWeather", "queryWeather", "weatherAlert"));
        GROUP_GUIDANCE.put("实时天气", "用户询问当前/未来/特定地点天气、温度、空气质量等时使用；严禁凭记忆回答天气，必须调用工具获取实时数据。");

        TOOL_GROUPS.put("图像多模态", List.of("analyzeImage", "generateImage", "editImage", "compareImages"));
        GROUP_GUIDANCE.put("图像多模态", "区分「分析已有图」vs「从零生成」vs「基于已有图修改」；指代「这张图/刚才的图」时倾向 editImage。");

        TOOL_GROUPS.put("文档/文件分析", List.of("analyzeFile"));
        GROUP_GUIDANCE.put("文档/文件分析", "用户问题与已上传文件内容相关时使用，严禁用训练数据代替文件内容。");

        TOOL_GROUPS.put("语音合成", List.of("synthesizeSpeech"));
        GROUP_GUIDANCE.put("语音合成", "用户要求读出来/转成语音/朗读/TTS 时使用。");

        TOOL_GROUPS.put("联网搜索", List.of("webSearch", "professionalSearch"));
        GROUP_GUIDANCE.put("联网搜索", "涉及实时信息（新闻/股价/比分/政策）、时间敏感问题，或明确包含「搜索/查一下/网上怎么说」时使用。");

        TOOL_GROUPS.put("附近服务", List.of("searchNearbyService"));
        GROUP_GUIDANCE.put("附近服务", "查找附近宠物医院、24小时急诊、诊所、植物医院、宠物店、园艺店等服务时使用。");

        TOOL_GROUPS.put("时间查询", List.of("getCurrentTime"));
        GROUP_GUIDANCE.put("时间查询", "询问当前时间/日期时使用。");

        TOOL_GROUPS.put("护理/健康管理", List.of(
                "createCareReminder", "queryPetCare", "queryPlantSafety", "queryFoodSafety",
                "saveMedication", "generateCarePlan", "checkMedication", "completeCareReminder",
                "listCareReminders", "triageSymptoms", "diagnoseDisease"));
        GROUP_GUIDANCE.put("护理/健康管理", "宠物/植物养护、喂药、疫苗、症状诊断、用药记录、护理提醒等场景使用。");
    }

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

        String effectiveSystemPrompt = (systemPrompt != null && !systemPrompt.isEmpty())
                ? systemPrompt
                : buildGroupedSystemPrompt(allowedToolNames);
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
     * 构建按功能分组的默认系统提示词。调用方未提供 systemPrompt 时使用。
     * 只会列出当前已注册、且在 allowedTools 范围内的工具，避免提示词与实际 schema 不一致。
     */
    private String buildGroupedSystemPrompt(Set<String> allowedToolNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 角色与核心目标\n");
        sb.append("你是具备多模态感知能力的智能助手。你的核心任务是精准识别用户意图，匹配并调用正确的工具；")
          .append("仅当请求属于通用知识问答或日常闲聊时，才直接回答文本。\n\n");

        sb.append("# 安全与隐私\n");
        sb.append("1. 绝对禁止输出任何本地文件路径、服务器路径、URL路径等敏感信息。\n");
        sb.append("2. 不要提及任何技术实现细节（如文件存储位置、API调用方式）。\n\n");

        sb.append("# 工具功能分组导航\n");
        sb.append("以下是当前可用的工具（按功能分组）。请先判断用户意图所属功能分组，再调用组内对应工具：\n\n");

        for (Map.Entry<String, List<String>> group : TOOL_GROUPS.entrySet()) {
            List<String> available = group.getValue().stream()
                    .filter(toolRegistry::containsKey)
                    .filter(t -> allowedToolNames == null || allowedToolNames.isEmpty() || allowedToolNames.contains(t))
                    .collect(Collectors.toList());
            if (available.isEmpty()) {
                continue;
            }
            sb.append("## ").append(group.getKey()).append("\n");
            sb.append("- 工具：").append(String.join(" / ", available)).append("\n");
            String guidance = GROUP_GUIDANCE.get(group.getKey());
            if (guidance != null) {
                sb.append("  ").append(guidance).append("\n");
            }
            sb.append("\n");
        }

        sb.append("# 多工具协作\n");
        sb.append("当请求需要多个工具协作时，按逻辑顺序调用（先获取数据，再基于数据生成/合成）。")
          .append("例如「查杭州天气并画西湖风景图」→ 先 getWeather 再 generateImage。\n\n");

        sb.append("# 决策兜底\n");
        sb.append("纯文本兜底：仅当请求是通用知识问答、日常闲聊，且完全不涉及上述任何功能分组特征时，才直接生成文本回答。");
        return sb.toString();
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
