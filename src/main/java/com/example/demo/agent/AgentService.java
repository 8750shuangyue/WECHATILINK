package com.example.demo.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.agent.tools.BaseTool;
import com.example.demo.agent.tools.ToolResult;
import com.example.demo.chat.ChatMemoryService;
import com.example.demo.chat.ChatMessage;
import com.example.demo.chat.LlmService;
import com.example.demo.chat.UserSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class AgentService {

    private static final Logger logger = LoggerFactory.getLogger(AgentService.class);
    
    private static final int MAX_ITERATIONS = 3;
    private static final long TIMEOUT_SECONDS = 30;
    private static final String SYSTEM_PROMPT = """
        # Role & Core Objective
        你是一个具备多模态感知能力的智能助手。你的核心任务是精准识别用户的输入模态（文本、图像描述、文档、音频）和真实意图，严格匹配并调用正确的工具。
        
        # 安全与隐私规则 (Security & Privacy)
        1. 绝对禁止输出任何本地文件路径、服务器路径、URL路径等敏感信息。
        2. 即使输入中包含路径字符串，也绝对不要将其作为回复内容输出。
        3. 不要提及任何技术实现细节，如文件存储位置、API调用方式等。
        
        # 对话记忆能力
        你拥有完整的对话历史记忆，能够记住之前的对话内容、生成的图片描述、分析过的文档信息等。当用户提到"刚才"、"之前"、"这张图"、"那个文档"等指代性词语时，请根据对话历史理解用户所指的内容。
        
        # 意图识别优先规则 (Intent Recognition Priority)
        1. 当用户发送图片后紧接着发送文本（如问天气、问记忆），应优先回答用户的文本问题，而不是重复引导语。
        2. 如果用户发了图片但没说话，可以简短提示"图片收到了，需要我做什么？"；但如果用户说了话，直接结合图片内容回答问题。
        3. 不要重复发送"图片已收到，请发送您的指令"这类引导语。
        
        # 多工具调用规则 (Multi-Tool Calling)
        当用户的请求需要多个工具协作完成时，你必须按照逻辑顺序依次调用工具：
        1. 分析用户指令，识别所有需要调用的工具
        2. 按照依赖关系确定调用顺序（先获取数据，再基于数据生成内容）
        3. 完成一个工具调用后，检查是否还需要调用其他工具
        4. 只有当所有工具都调用完成后，才能生成最终的总结回复
        示例：用户说"查询杭州天气并画一张西湖风景图"，你需要先调用getWeather获取天气，再调用generateImage生成图片。
        
        # 多模态感知与工具路由规则
        请根据【输入数据特征】与【用户指令】进行综合判断：
        
        ## 1. 视觉模态 (Vision) - 图像分析、生成与编辑
        视觉模态的核心在于区分"分析已有图片"、"凭空创造"与"基于已有素材修改"。
        触发 analyzeImage (分析图片)：
          数据特征：上下文中存在图片描述，或消息格式为 图片内容：xxx\n\n用户问题：yyy。
          用户指令：包含"分析"、"描述"、"识别"、"提取文字"、"总结图片内容"、"图片里有什么"等。
          注意：分析图片只返回文字描述，不会生成新图片。
        触发 generateImage (生成新图)：
          数据特征：上下文中没有图片描述或历史图片。
          用户指令：包含"画一张"、"生成"、"创作"、"设计一个"等从零开始的表述。
        触发 editImage (编辑已有图)：
          数据特征：上下文中存在图片描述，或消息格式为 图片内容：xxx\n\n用户问题：yyy。
          用户指令：包含"修改"、"换成"、"去掉"、"加上"、"风格转换"等。
          指代消解：当用户提到"这张图"、"刚才的图"、"上一张"、"帮我改一下"等，强制调用 editImage。
          隐式意图：如果用户针对已有图片提问（如"把这里的天空变蓝"），即使没有明确说"修改"，也必须调用 editImage。
          
        ## 2. 文档模态 (Document) - 文件分析
        触发 analyzeFile：
          数据特征：系统上下文中提示用户上传了文件（PDF, Word, Excel, TXT等）。
          用户指令：包含"总结"、"提取"、"分析"、"翻译这个文件"、"表格里有什么"等。
          注意：只要用户的问题与已上传的文件内容相关，必须调用此工具，严禁用自身训练数据回答文件内容。
          
        ## 3. 语音模态 (Audio) - 语音合成
        触发 synthesizeSpeech：
          用户指令：包含"读出来"、"转成语音"、"朗读"、"TTS"、"用声音回答"等。
          注意：如果用户只是发送了一段语音（ASR转文本），请根据文本内容判断意图，不要仅仅因为收到了语音就调用合成工具。
          
        ## 4. 实时信息模态 (Real-time) - 天气查询
        触发 getWeather：
          用户指令：询问当前、未来、特定地点的天气、温度、空气质量等。
          禁忌：严禁凭记忆回答天气，必须调用工具获取实时数据。
          
        # 决策逻辑与兜底机制 (Fallback)
        模态冲突处理：如果用户同时提供了图片和文件，请根据用户最新的文本指令决定调用哪个工具。如果指令模糊，优先处理最新上传的模态。
        纯文本兜底：只有当用户的请求是通用知识问答、日常闲聊，且完全不涉及上述任何模态特征和工具触发条件时，才直接生成文本回答。
        格式解析：当你收到 图片内容：[描述]\n\n用户问题：[指令] 格式时，[描述] 是你唯一的视觉输入，请严格根据 [指令] 判断是否需要编辑。
        
        # Few-Shot Examples (多模态示例)
        
        [纯文本] User: 什么是量子力学？
        Assistant: 量子力学是研究物质世界微观粒子运动规律的物理学分支... (直接回答)
        
        [视觉-分析] 系统提示: 图片内容：一只坐在草地上的金毛犬。
        User: 分析这张图片的内容。
        Assistant: [调用 analyzeImage]
        
        [视觉-生成] User: 帮我画一只赛博朋克风格的猫。
        Assistant: [调用 generateImage]
        
        [视觉-编辑] 系统提示: 图片内容：一只坐在草地上的金毛犬。
        User: 把草地换成沙滩。
        Assistant: [调用 editImage]
        
        [视觉-隐式编辑] 系统提示: 图片内容：一张带有黑色边框的风景照。
        User: 这个边框太丑了，去掉它。
        Assistant: [调用 editImage]
        
        [文档] 系统提示: 用户已上传文件 2023财报.pdf
        User: 帮我总结一下这份财报的核心利润。
        Assistant: [调用 analyzeFile]
        
        [语音] User: 请把刚才生成的图片描述读给我听。
        Assistant: [调用 synthesizeSpeech]
        
        [多工具-天气+图片] User: 查询杭州今天的天气，并根据天气画一张西湖风景图。
        Assistant: [先调用 getWeather 获取杭州天气]
        (工具返回: 杭州今天晴天，25度)
        Assistant: [再调用 generateImage，根据天气生成西湖风景图]
        (工具返回: 图片生成成功)
        Assistant: 杭州今天天气晴朗，气温25度，适合游览。已为您生成西湖风景图。
        
        [多工具-文档+语音] User: 分析这份文档，并用语音读给我听。
        Assistant: [先调用 analyzeFile 分析文档内容]
        (工具返回: 文档分析结果...)
        Assistant: [再调用 synthesizeSpeech 将分析结果转为语音]
        (工具返回: 语音合成成功)
        Assistant: 文档分析完成，已为您合成语音。
        
        [多工具-天气+语音] User: 查一下北京的天气，然后读给我听。
        Assistant: [先调用 getWeather 查询北京天气]
        (工具返回: 北京今天多云，28度)
        Assistant: [再调用 synthesizeSpeech 将天气信息转为语音]
        (工具返回: 语音合成成功)
        Assistant: 北京今天多云，气温28度。已为您合成语音。
        """;

    private final LlmService llmService;
    private final ChatMemoryService chatMemoryService;
    private final GlobalExceptionHandler exceptionHandler;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final UserSessionService userSessionService;
    private final Map<String, BaseTool> toolRegistry = new ConcurrentHashMap<>();

    @Autowired
    public AgentService(LlmService llmService, ChatMemoryService chatMemoryService, 
                        GlobalExceptionHandler exceptionHandler, SensitiveWordFilter sensitiveWordFilter,
                        UserSessionService userSessionService, List<BaseTool> tools) {
        this.llmService = llmService;
        this.chatMemoryService = chatMemoryService;
        this.exceptionHandler = exceptionHandler;
        this.sensitiveWordFilter = sensitiveWordFilter;
        this.userSessionService = userSessionService;
        
        for (BaseTool tool : tools) {
            toolRegistry.put(tool.getName(), tool);
            logger.info("Registered tool: {}", tool.getName());
        }
    }

    public AgentResult runAgent(String conversationId, String userMessage) {
        return runAgent(conversationId, userMessage, null);
    }

    private static final long PROGRESS_NOTIFY_SECONDS = 10;
    
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String message);
    }
    
    public AgentResult runAgent(String conversationId, String userMessage, String fileInfo) {
        return runAgent(conversationId, userMessage, fileInfo, null);
    }
    
    public AgentResult runAgent(String conversationId, String userMessage, String fileInfo, ProgressCallback progressCallback) {
        logger.info("Running agent, conversationId: {}, userMessage: {}, fileInfo: {}", 
                conversationId, userMessage, fileInfo);

        final long startTime = System.currentTimeMillis();
        final java.util.concurrent.atomic.AtomicBoolean progressSent = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.ScheduledFuture<?>[] progressTask = new java.util.concurrent.ScheduledFuture<?>[1];
        
        java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        
        progressTask[0] = scheduler.schedule(() -> {
            if (!progressSent.get()) {
                progressSent.set(true);
                long elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000;
                logger.info("Sending progress notification after {} seconds for conversation: {}", elapsedSeconds, conversationId);
                if (progressCallback != null) {
                    try {
                        progressCallback.onProgress("正在努力生成中，请稍候...");
                    } catch (Exception e) {
                        logger.error("Failed to send progress notification", e);
                    }
                }
            }
        }, PROGRESS_NOTIFY_SECONDS, TimeUnit.SECONDS);

        try {
            AgentResult result = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return executeAgentLoop(conversationId, userMessage, fileInfo);
                } catch (Exception e) {
                    logger.error("Agent execution failed", e);
                    return AgentResult.failure(exceptionHandler.handleException(e));
                }
            }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            return result;
            
        } catch (TimeoutException e) {
            logger.error("Agent execution timed out after {} seconds", TIMEOUT_SECONDS);
            return AgentResult.failure("由于网络原因，我处理不过来了，请稍后再试。");
        } catch (Exception e) {
            logger.error("Agent execution interrupted", e);
            return AgentResult.failure(exceptionHandler.handleException(e));
        } finally {
            if (progressTask[0] != null) {
                progressTask[0].cancel(false);
            }
            scheduler.shutdown();
        }
    }

    private AgentResult executeAgentLoop(String conversationId, String userMessage, String fileInfo) throws Exception {
        String effectiveMessage = userMessage;
        if (fileInfo != null && !fileInfo.isEmpty()) {
            effectiveMessage = "用户上传了文件：" + fileInfo + "\n\n用户问题：" + (userMessage != null ? userMessage : "请分析这个文件");
        }
        logger.info("Effective message: {}", effectiveMessage.length() > 100 ? effectiveMessage.substring(0, 100) + "..." : effectiveMessage);

        JSONArray messages = buildContextMessages(conversationId, effectiveMessage);
        
        JSONArray tools = buildToolsSchema();
        
        int iteration = 0;
        int consecutiveErrors = 0;
        
        String lastImageResultPath = null;
        String lastAudioResultPath = null;
        String lastTextResult = null;
        
        while (iteration < MAX_ITERATIONS) {
            iteration++;
            logger.info("Agent iteration: {}/{}", iteration, MAX_ITERATIONS);

            JSONObject response;
            try {
                response = llmService.chatWithTools(messages, tools);
            } catch (Exception e) {
                logger.error("LLM API call failed in iteration {}", iteration, e);
                consecutiveErrors++;
                if (consecutiveErrors >= 2) {
                    throw e;
                }
                continue;
            }
            
            if (response == null) {
                logger.warn("LLM returned null response in iteration {}", iteration);
                consecutiveErrors++;
                if (consecutiveErrors >= 2) {
                    throw new RuntimeException("LLM API returned null response");
                }
                continue;
            }

            try {
                if (ToolCall.hasToolCalls(response)) {
                    List<ToolCall> toolCalls = ToolCall.parseToolCalls(response);
                    
                    for (ToolCall toolCall : toolCalls) {
                        String toolName = toolCall.getToolName();
                        JSONObject arguments = toolCall.getArguments();
                        
                        if ("editImage".equals(toolName) && !arguments.containsKey("userId")) {
                            arguments.put("userId", conversationId);
                            logger.info("Auto-filled userId for editImage tool: {}", conversationId);
                        }
                        
                        if ("analyzeImage".equals(toolName) && !arguments.containsKey("userId")) {
                            arguments.put("userId", conversationId);
                            logger.info("Auto-filled userId for analyzeImage tool: {}", conversationId);
                        }
                        
                        if ("analyzeFile".equals(toolName)) {
                            if (!arguments.containsKey("fileUrl")) {
                                logger.warn("analyzeFile missing fileUrl, trying to extract from fileInfo");
                                if (fileInfo != null) {
                                    int urlIndex = fileInfo.indexOf("fileUrl=");
                                    if (urlIndex != -1) {
                                        String url = fileInfo.substring(urlIndex + 8);
                                        int commaIndex = url.indexOf(",");
                                        if (commaIndex != -1) {
                                            url = url.substring(0, commaIndex);
                                        }
                                        arguments.put("fileUrl", url);
                                        logger.info("Auto-extracted fileUrl from fileInfo: {}", url);
                                    }
                                }
                            }
                            if (!arguments.containsKey("fileName")) {
                                logger.warn("analyzeFile missing fileName, trying to extract from fileInfo");
                                if (fileInfo != null) {
                                    int nameIndex = fileInfo.indexOf("fileName=");
                                    if (nameIndex != -1) {
                                        String name = fileInfo.substring(nameIndex + 10);
                                        int commaIndex = name.indexOf(",");
                                        if (commaIndex != -1) {
                                            name = name.substring(0, commaIndex);
                                        }
                                        arguments.put("fileName", name);
                                        logger.info("Auto-extracted fileName from fileInfo: {}", name);
                                    }
                                }
                            }
                        }
                        
                        logger.info("Executing tool call: {}, arguments: {}", toolName, arguments);
                        
                        BaseTool tool = toolRegistry.get(toolName);
                        if (tool == null) {
                            logger.warn("Unknown tool: {}", toolName);
                            messages.add(createToolMessage(toolCall.getId(), toolName, "未知工具: " + toolName));
                            continue;
                        }
                        
                        ToolResult<?> result;
                        try {
                            result = tool.execute(arguments);
                        } catch (Exception e) {
                            logger.error("Tool execution failed for tool: {}, arguments: {}", toolName, arguments, e);
                            messages.add(createToolMessage(toolCall.getId(), toolName, 
                                    exceptionHandler.handleToolExecutionException(toolName, e)));
                            continue;
                        }
                        
                        String resultText;
                        if (result.isSuccess()) {
                            if ("synthesizeSpeech".equals(toolName) && result.getData() instanceof String) {
                                lastAudioResultPath = (String) result.getData();
                                resultText = "语音合成完成";
                                chatMemoryService.saveMessagePair(conversationId, userMessage, "语音合成完成");
                            } else if ("generateImage".equals(toolName) && result.getData() instanceof String) {
                                lastImageResultPath = (String) result.getData();
                                logger.info("Image generated successfully, path: {}", lastImageResultPath);
                                String imagePrompt = arguments.getString("prompt");
                                String imageStyle = arguments.getString("style");
                                resultText = "图片生成成功";
                                String imageDesc = "已生成图片：" + (imagePrompt != null ? imagePrompt : "");
                                if (imageStyle != null && !imageStyle.isEmpty()) {
                                    imageDesc += "，风格：" + imageStyle;
                                }
                                chatMemoryService.saveMessagePair(conversationId, userMessage, imageDesc);
                            } else if ("editImage".equals(toolName) && result.getData() instanceof String) {
                                lastImageResultPath = (String) result.getData();
                                logger.info("Image edited successfully, path: {}", lastImageResultPath);
                                String editPrompt = arguments.getString("prompt");
                                if (editPrompt == null) {
                                    editPrompt = arguments.getString("description");
                                }
                                resultText = "图片编辑成功";
                                chatMemoryService.saveMessagePair(conversationId, userMessage, "已编辑图片：" + (editPrompt != null ? editPrompt : ""));
                            } else if ("analyzeImage".equals(toolName) && result.getData() instanceof String) {
                                resultText = (String) result.getData();
                                logger.info("Image analysis completed, result length: {} chars", resultText.length());
                                lastTextResult = resultText;
                                chatMemoryService.saveMessagePair(conversationId, userMessage, resultText);
                            } else {
                                resultText = formatToolResult(result.getData());
                                if ("analyzeFile".equals(toolName) || "getWeather".equals(toolName)) {
                                    lastTextResult = resultText;
                                    chatMemoryService.saveMessagePair(conversationId, userMessage, resultText);
                                }
                                
                                if ("getWeather".equals(toolName)) {
                                    boolean needsImageAfterWeather = userMessage != null && 
                                            (userMessage.contains("画") || userMessage.contains("生成图片") || 
                                            userMessage.contains("生成一张") || userMessage.contains("画图") || 
                                            userMessage.contains("画一张") || userMessage.contains("图片")) &&
                                            !userMessage.contains("分析") && !userMessage.contains("描述") && 
                                            !userMessage.contains("识别") && !userMessage.contains("提取");
                                    
                                    if (needsImageAfterWeather && lastImageResultPath == null) {
                                        logger.info("Detected image request after weather, automatically calling generateImage");
                                        BaseTool generateImageTool = toolRegistry.get("generateImage");
                                        if (generateImageTool != null) {
                                            String imagePrompt = extractImagePrompt(userMessage);
                                            if (!imagePrompt.contains("天气") && !imagePrompt.contains("温度")) {
                                                imagePrompt = resultText.substring(0, Math.min(100, resultText.length())) + "，" + imagePrompt;
                                            }
                                            JSONObject args = new JSONObject();
                                            args.put("prompt", imagePrompt);
                                            try {
                                                ToolResult<?> imageResult = generateImageTool.execute(args);
                                                if (imageResult.isSuccess() && imageResult.getData() instanceof String) {
                                                    lastImageResultPath = (String) imageResult.getData();
                                                    logger.info("Auto-generated image after weather, path: {}", lastImageResultPath);
                                                    messages.add(createToolMessage("auto", "generateImage", "图片生成成功"));
                                                    String imageDesc = "已生成图片：" + imagePrompt;
                                                    chatMemoryService.saveMessagePair(conversationId, userMessage, imageDesc);
                                                }
                                            } catch (Exception e) {
                                                logger.error("Auto generateImage call failed", e);
                                            }
                                        }
                                    }
                                }
                            }
                            logger.info("Tool execution successful: {}, result type: {}, result length: {}", 
                                    toolName, result.getData() != null ? result.getData().getClass().getSimpleName() : "null",
                                    resultText != null ? resultText.length() : 0);
                        } else {
                            resultText = result.getMessage();
                            logger.warn("Tool execution failed: {}, message: {}", toolName, resultText);
                        }
                        
                        messages.add(createToolMessage(toolCall.getId(), toolName, resultText));
                    }
                    
                } else {
                    String reply = ToolCall.getTextContent(response);
                    
                    if (reply != null && !reply.trim().isEmpty()) {
                        String filteredReply = sensitiveWordFilter.filter(reply);
                        
                        if (iteration == 1 && shouldUseToolDirectly(userMessage, reply)) {
                            logger.info("LLM didn't call tool but message is tool-related, trying direct tool call");
                            AgentResult toolResult = tryDirectToolCall(conversationId, userMessage);
                            if (toolResult.isSuccess()) {
                                return toolResult;
                            }
                        }
                        
                        AgentResult parsedToolResult = tryParseNaturalLanguageToolCall(conversationId, filteredReply);
                        if (parsedToolResult.isSuccess()) {
                            return parsedToolResult;
                        }
                        
                        lastTextResult = filteredReply;
                        chatMemoryService.saveMessagePair(conversationId, userMessage, filteredReply);
                        
                        boolean needsImage = userMessage != null && (userMessage.contains("画") || userMessage.contains("生成图片") || 
                                userMessage.contains("生成一张") || userMessage.contains("画图") || userMessage.contains("画一张")) &&
                                !userMessage.contains("分析") && !userMessage.contains("描述") && !userMessage.contains("识别") && !userMessage.contains("提取");
                        boolean needsAudio = userMessage != null && (userMessage.contains("语音") || userMessage.contains("朗读") || 
                                userMessage.contains("TTS") || userMessage.contains("读出来") || userMessage.contains("读给我听") || 
                                userMessage.contains("合成语音") || userMessage.contains("播报") || userMessage.contains("语音回复") || userMessage.contains("语音消息"));
                        boolean needsImageAnalysis = userMessage != null && (userMessage.contains("分析图片") || userMessage.contains("描述图片") || 
                                userMessage.contains("识别图片") || userMessage.contains("提取图片") || userMessage.contains("图片里有") || userMessage.contains("图片内容") ||
                                userMessage.contains("分析这张图") || userMessage.contains("描述这张图") || userMessage.contains("图里有") || 
                                userMessage.contains("描述一下") || userMessage.contains("图里是什么") || userMessage.contains("图里有什么") ||
                                userMessage.contains("[待分析图片]"));
                        
                        if (needsImage && lastImageResultPath == null) {
                            boolean hasPendingImage = conversationId != null && userSessionService != null && 
                                    userSessionService.hasPendingImage(conversationId);
                            
                            if (hasPendingImage) {
                                logger.info("LLM returned text but editImage not called, forcing direct call (image context exists)");
                                BaseTool editImageTool = toolRegistry.get("editImage");
                                if (editImageTool != null) {
                                    String imagePrompt = extractImagePrompt(userMessage);
                                    JSONObject args = new JSONObject();
                                    args.put("prompt", imagePrompt);
                                    args.put("userId", conversationId);
                                    try {
                                        ToolResult<?> imageResult = editImageTool.execute(args);
                                        if (imageResult.isSuccess() && imageResult.getData() instanceof String) {
                                            lastImageResultPath = (String) imageResult.getData();
                                            logger.info("Forced editImage call successful, path: {}", lastImageResultPath);
                                            messages.add(createToolMessage("forced", "editImage", "图片编辑成功"));
                                        } else {
                                            logger.warn("Forced editImage call failed: {}", imageResult.getMessage());
                                        }
                                    } catch (Exception e) {
                                        logger.error("Forced editImage call exception", e);
                                    }
                                } else {
                                    logger.warn("editImage tool not found in registry");
                                }
                            } else {
                                logger.info("LLM returned text but generateImage not called, forcing direct call");
                                BaseTool generateImageTool = toolRegistry.get("generateImage");
                                if (generateImageTool != null) {
                                    String imagePrompt = extractImagePrompt(userMessage);
                                    JSONObject args = new JSONObject();
                                    args.put("prompt", imagePrompt);
                                    try {
                                        ToolResult<?> imageResult = generateImageTool.execute(args);
                                        if (imageResult.isSuccess() && imageResult.getData() instanceof String) {
                                            lastImageResultPath = (String) imageResult.getData();
                                            logger.info("Forced generateImage call successful, path: {}", lastImageResultPath);
                                            messages.add(createToolMessage("forced", "generateImage", "图片生成成功"));
                                        } else {
                                            logger.warn("Forced generateImage call failed: {}", imageResult.getMessage());
                                        }
                                    } catch (Exception e) {
                                        logger.error("Forced generateImage call exception", e);
                                    }
                                } else {
                                    logger.warn("generateImage tool not found in registry");
                                }
                            }
                            continue;
                        } else if (needsAudio && lastAudioResultPath == null) {
                            logger.info("LLM returned text but synthesizeSpeech not called, forcing direct call");
                            BaseTool ttsTool = toolRegistry.get("synthesizeSpeech");
                            if (ttsTool != null) {
                                JSONObject args = new JSONObject();
                                args.put("text", lastTextResult != null ? lastTextResult : "");
                                try {
                                    ToolResult<?> ttsResult = ttsTool.execute(args);
                                    if (ttsResult.isSuccess() && ttsResult.getData() instanceof String) {
                                        lastAudioResultPath = (String) ttsResult.getData();
                                        logger.info("Forced synthesizeSpeech call successful, path: {}", lastAudioResultPath);
                                        messages.add(createToolMessage("forced", "synthesizeSpeech", "语音合成成功"));
                                    } else {
                                        logger.warn("Forced synthesizeSpeech call failed: {}", ttsResult.getMessage());
                                    }
                                } catch (Exception e) {
                                    logger.error("Forced synthesizeSpeech call exception", e);
                                }
                            } else {
                                logger.warn("synthesizeSpeech tool not found in registry");
                            }
                            continue;
                        } else if (needsImageAnalysis) {
                            logger.info("LLM returned text but analyzeImage not called, checking if image already analyzed");
                            boolean imageAlreadyAnalyzed = false;
                            if (conversationId != null && userSessionService != null) {
                                imageAlreadyAnalyzed = !userSessionService.hasUnanalyzedImage(conversationId);
                            }
                            if (imageAlreadyAnalyzed) {
                                logger.info("Image already analyzed, skipping forced analyzeImage call");
                            } else {
                                BaseTool analyzeImageTool = toolRegistry.get("analyzeImage");
                                if (analyzeImageTool != null) {
                                    JSONObject args = new JSONObject();
                                    args.put("userId", conversationId);
                                    args.put("prompt", userMessage);
                                    try {
                                        ToolResult<?> imageResult = analyzeImageTool.execute(args);
                                        if (imageResult.isSuccess() && imageResult.getData() instanceof String) {
                                            lastTextResult = (String) imageResult.getData();
                                            logger.info("Forced analyzeImage call successful, result length: {} chars", lastTextResult.length());
                                            messages.add(createToolMessage("forced", "analyzeImage", lastTextResult));
                                        } else {
                                            logger.warn("Forced analyzeImage call failed: {}", imageResult.getMessage());
                                        }
                                    } catch (Exception e) {
                                        logger.error("Forced analyzeImage call exception", e);
                                    }
                                } else {
                                    logger.warn("analyzeImage tool not found in registry");
                                }
                            }
                            continue;
                        } else {
                            logger.info("LLM returned text after tool execution, breaking loop");
                            break;
                        }
                    } else {
                        logger.warn("LLM returned empty content in iteration {}", iteration);
                        consecutiveErrors++;
                        if (consecutiveErrors >= 2) {
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                logger.error("Error processing LLM response in iteration {}", iteration, e);
                consecutiveErrors++;
                if (consecutiveErrors >= 2) {
                    throw e;
                }
            }
        }
        
        if (lastAudioResultPath != null) {
            logger.info("Returning audio result: {}", lastAudioResultPath);
            return AgentResult.successWithAudio(lastTextResult != null ? lastTextResult : "语音合成完成", lastAudioResultPath);
        }
        
        if (lastImageResultPath != null) {
            logger.info("Returning image result: {}, text: {}", lastImageResultPath, lastTextResult);
            String imagePrompt = extractImagePrompt(userMessage);
            String imageSuffix = "\n\n已为您生成图片：" + imagePrompt;
            String combinedText = (lastTextResult != null ? lastTextResult : "图片处理完成") + imageSuffix;
            return AgentResult.successWithImage(combinedText, lastImageResultPath);
        }
        
        if (lastTextResult != null) {
            logger.info("Returning text result: {}", lastTextResult);
            return AgentResult.success(lastTextResult);
        }
        
        logger.warn("No result available, returning failure");
        return AgentResult.failure("处理请求时发生错误");
    }

    private static final int MAX_HISTORY_MESSAGES = 10;

    private JSONArray buildContextMessages(String conversationId, String userMessage) {
        JSONArray messages = new JSONArray();
        
        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", SYSTEM_PROMPT);
        messages.add(systemMessage);
        
        List<ChatMessage> history;
        try {
            history = chatMemoryService.getConversationHistory(conversationId);
        } catch (Exception e) {
            logger.warn("Failed to get conversation history", e);
            history = List.of();
        }
        
        int startIndex = Math.max(0, history.size() - MAX_HISTORY_MESSAGES);
        for (int i = startIndex; i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            String content = msg.getContent();
            
            if (content != null && (content.contains(".png") || content.contains(".jpg") || 
                    content.contains(".jpeg") || content.contains(".gif") || 
                    content.contains(".bmp") || content.contains("/uploads/") || 
                    content.contains("\\uploads\\") || content.contains("file:///"))) {
                continue;
            }
            
            JSONObject messageObj = new JSONObject();
            messageObj.put("role", msg.getRole());
            messageObj.put("content", content);
            messages.add(messageObj);
        }
        
        logger.info("Added {} historical messages (total history: {})", 
                history.size() - startIndex, history.size());
        
        JSONObject userMessageObj = new JSONObject();
        userMessageObj.put("role", "user");
        userMessageObj.put("content", userMessage);
        messages.add(userMessageObj);
        
        return messages;
    }

    private JSONArray buildToolsSchema() {
        JSONArray tools = new JSONArray();
        
        for (BaseTool tool : toolRegistry.values()) {
            try {
                Map<String, Object> schema = tool.getDefinition().toSchema();
                tools.add(schema);
                logger.info("Built tool schema for {}: {}", tool.getName(), JSON.toJSONString(schema));
            } catch (Exception e) {
                logger.error("Failed to build schema for tool: {}", tool.getName(), e);
            }
        }
        
        logger.info("Total tools in schema: {}", tools.size());
        return tools;
    }

    private JSONObject createToolMessage(String toolCallId, String toolName, String content) {
        JSONObject message = new JSONObject();
        message.put("role", "tool");
        message.put("content", content);
        message.put("tool_call_id", toolCallId);
        return message;
    }

    private JSONObject createAssistantToolCallMessage(JSONObject response) {
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", "");
        
        try {
            JSONArray choices = response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                JSONObject output = response.getJSONObject("output");
                if (output != null) {
                    choices = output.getJSONArray("choices");
                }
            }
            
            if (choices != null && !choices.isEmpty()) {
                JSONObject choice = choices.getJSONObject(0);
                if (choice != null) {
                    JSONObject originalMessage = choice.getJSONObject("message");
                    if (originalMessage != null) {
                        message.put("tool_calls", originalMessage.get("tool_calls"));
                        
                        Object contentObj = originalMessage.get("content");
                        if (contentObj != null) {
                            message.put("content", contentObj);
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to create assistant tool call message", e);
        }
        
        return message;
    }

    private String formatToolResult(Object data) {
        if (data == null) {
            return "";
        }
        
        if (data instanceof String) {
            return (String) data;
        }
        
        if (data instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) data;
            if (map.containsKey("summary")) {
                return String.valueOf(map.get("summary"));
            }
            return JSON.toJSONString(data);
        }
        
        return data.toString();
    }

    private String extractImagePrompt(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return "美丽的风景";
        }
        
        String prompt = userMessage;
        
        prompt = prompt.replaceAll("画(一)?张?", "").trim();
        prompt = prompt.replaceAll("生成(一)?张?", "").trim();
        prompt = prompt.replaceAll("画个?", "").trim();
        prompt = prompt.replaceAll("生成个?", "").trim();
        
        if (prompt.isEmpty()) {
            prompt = "美丽的风景";
        }
        
        return prompt;
    }

    private boolean shouldUseToolDirectly(String userMessage, String llmReply) {
        if (userMessage == null || llmReply == null) {
            return false;
        }
        
        String lowerMessage = userMessage.toLowerCase();
        String lowerReply = llmReply.toLowerCase();
        
        boolean isWeatherRequest = lowerMessage.contains("天气") || lowerMessage.contains("气温") || 
                                    lowerMessage.contains("温度") || lowerMessage.contains("预报");
        
        boolean llmSaysCantDoIt = lowerReply.contains("无法") || lowerReply.contains("不能") || 
                                   lowerReply.contains("查询") || lowerReply.contains("工具") ||
                                   lowerReply.contains("网站") || lowerReply.contains("app");
        
        return isWeatherRequest && llmSaysCantDoIt;
    }

    private AgentResult tryDirectToolCall(String conversationId, String userMessage) {
        try {
            BaseTool weatherTool = toolRegistry.get("getWeather");
            if (weatherTool != null) {
                String city = extractCity(userMessage);
                if (city != null) {
                    logger.info("Directly calling getWeather tool for city: {}", city);
                    JSONObject params = new JSONObject();
                    params.put("city", city);
                    ToolResult<?> result = weatherTool.execute(params);
                    
                    if (result.isSuccess()) {
                        String reply = "根据实时数据，" + formatToolResult(result.getData());
                        chatMemoryService.saveMessagePair(conversationId, userMessage, reply);
                        return AgentResult.success(reply);
                    }
                }
            }
            
            BaseTool fileTool = toolRegistry.get("analyzeFile");
            if (fileTool != null && userMessage.contains("文件")) {
                logger.info("Directly calling analyzeFile tool");
                JSONObject params = new JSONObject();
                params.put("userQuery", userMessage);
                ToolResult<?> result = fileTool.execute(params);
                
                if (result.isSuccess()) {
                    String reply = formatToolResult(result.getData());
                    chatMemoryService.saveMessagePair(conversationId, userMessage, reply);
                    return AgentResult.success(reply);
                }
            }
        } catch (Exception e) {
            logger.error("Direct tool call failed", e);
        }
        
        return AgentResult.failure("");
    }

    private String extractCity(String message) {
        if (message == null) {
            return null;
        }
        
        String[] cityKeywords = {"北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "武汉", 
                                  "西安", "重庆", "天津", "苏州", "郑州", "长沙", "东莞", "佛山",
                                  "宁波", "青岛", "合肥", "无锡", "济南", "福州", "厦门", "沈阳",
                                  "大连", "长春", "哈尔滨", "石家庄", "太原", "呼和浩特", "南宁",
                                  "海口", "昆明", "贵阳", "拉萨", "兰州", "西宁", "银川", "乌鲁木齐"};
        
        for (String city : cityKeywords) {
            if (message.contains(city)) {
                return city;
            }
        }
        
        return null;
    }
    
    private AgentResult tryParseNaturalLanguageToolCall(String conversationId, String reply) {
        if (reply == null || reply.isEmpty()) {
            return AgentResult.failure("");
        }
        
        try {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("调用\\s*(\\w+)\\s*工具");
            java.util.regex.Matcher matcher = pattern.matcher(reply);
            
            if (matcher.find()) {
                String toolName = matcher.group(1);
                logger.info("Detected natural language tool call for: {}", toolName);
                
                BaseTool tool = toolRegistry.get(toolName);
                if (tool == null) {
                    return AgentResult.failure("");
                }
                
                JSONObject arguments = new JSONObject();
                
                java.util.regex.Pattern paramPattern = java.util.regex.Pattern.compile("(\\w+)\\s*[=:]\\s*[\"'“”]([^\"'“”]+)[\"'“”]");
                java.util.regex.Matcher paramMatcher = paramPattern.matcher(reply);
                
                while (paramMatcher.find()) {
                    String paramName = paramMatcher.group(1);
                    String paramValue = paramMatcher.group(2);
                    arguments.put(paramName, paramValue);
                    logger.info("Parsed parameter: {} = {}", paramName, paramValue);
                }
                
                if ("editImage".equals(toolName) && !arguments.containsKey("userId")) {
                    arguments.put("userId", conversationId);
                }
                
                if (!arguments.isEmpty()) {
                    ToolResult<?> result = tool.execute(arguments);
                    
                    if (result.isSuccess()) {
                        if ("synthesizeSpeech".equals(toolName) && result.getData() instanceof String) {
                            String audioFilePath = (String) result.getData();
                            chatMemoryService.saveMessagePair(conversationId, "语音合成", "语音合成完成");
                            return AgentResult.successWithAudio("语音合成完成", audioFilePath);
                        }
                        
                        if ("generateImage".equals(toolName) && result.getData() instanceof String) {
                            String imageFilePath = (String) result.getData();
                            chatMemoryService.saveMessagePair(conversationId, "图片生成", "图片生成完成");
                            return AgentResult.successWithImage("图片生成完成", imageFilePath);
                        }
                        
                        if ("editImage".equals(toolName) && result.getData() instanceof String) {
                            String imageFilePath = (String) result.getData();
                            chatMemoryService.saveMessagePair(conversationId, "图片编辑", "图片编辑完成");
                            return AgentResult.successWithImage("图片编辑完成", imageFilePath);
                        }
                        
                        String resultText = formatToolResult(result.getData());
                        chatMemoryService.saveMessagePair(conversationId, "工具调用", resultText);
                        return AgentResult.success(resultText);
                    } else {
                        logger.warn("Tool execution failed: {}", result.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Failed to parse natural language tool call", e);
        }
        
        return AgentResult.failure("");
    }
}