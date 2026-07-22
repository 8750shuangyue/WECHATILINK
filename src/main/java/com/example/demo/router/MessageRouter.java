package com.example.demo.router;

import com.example.demo.chat.ChatMemoryService;
import com.example.demo.chat.LlmService;
import com.example.demo.config.DashScopeConfig;
import com.example.demo.core.FileParserService;
import com.example.demo.imagegen.ImageGenerationService;
import com.example.demo.vision.ImageAnalysisResponse;
import com.example.demo.vision.VisionService;
import com.example.demo.weather.tool.WeatherTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import java.util.Arrays;
import java.util.List;

@Service
public class MessageRouter {

    private static final Logger logger = LoggerFactory.getLogger(MessageRouter.class);

    private final LlmService llmService;
    private final VisionService visionService;
    private final ImageGenerationService imageGenerationService;
    private final DashScopeConfig config;
    private final WeatherTool weatherTool;
    private final FileParserService fileParserService;
    private final ChatMemoryService chatMemoryService;

    private static final List<String> GENERATION_KEYWORDS = Arrays.asList(
            "生成图片", "生成", "画", "绘画", "创作", "制作", "设计", "创造", "帮我画", "帮我生成"
    );

    private static final List<String> WEATHER_KEYWORDS = Arrays.asList(
            "天气", "气温", "温度", "天气怎么样", "天气预报", "今天天气", "明天天气"
    );

    private static final List<String> TTS_KEYWORDS = Arrays.asList(
            "语音", "合成语音", "读", "朗读", "播报", "语音回复", "语音消息", "说", "念"
    );

    private static final List<String> EDIT_KEYWORDS = Arrays.asList(
            "修改", "编辑", "换", "改成", "换成", "风格", "转换", "添加", "去除", "增强", "修复", "换成", "变成"
    );

    public MessageRouter(LlmService llmService, VisionService visionService, 
                         ImageGenerationService imageGenerationService, DashScopeConfig config,
                         WeatherTool weatherTool,
                         FileParserService fileParserService, ChatMemoryService chatMemoryService) {
        this.llmService = llmService;
        this.visionService = visionService;
        this.imageGenerationService = imageGenerationService;
        this.config = config;
        this.weatherTool = weatherTool;
        this.fileParserService = fileParserService;
        this.chatMemoryService = chatMemoryService;
    }

    public RouteResult routeTextMessage(String userMessage) {
        logger.info("Routing message: '{}'", userMessage);
        try {
            RouteResult result = aiRouteTextMessage(userMessage);
            logger.info("AI routing successful: routeType={}, content={}", result.getRouteType(), result.getTextContent());
            return result;
        } catch (Exception e) {
            logger.warn("AI routing failed, falling back to keyword-based routing: {}", e.getMessage());
            RouteResult result = keywordRouteTextMessage(userMessage);
            logger.info("Keyword routing result: routeType={}, content={}", result.getRouteType(), result.getTextContent());
            return result;
        }
    }

    private RouteResult aiRouteTextMessage(String userMessage) throws Exception {
        String systemPrompt = """
            你是一个精准的消息路由助手。请严格按照规则分析用户消息并返回JSON。
            
            可用工具：
            - getWeather: 查询指定城市的天气信息
              参数: {"city": "城市名称"}
              示例: "北京天气" → 调用 getWeather("北京")
            
            路由规则（必须严格遵守）：
            - WEATHER：任何包含"天气"、"气温"、"温度"、"预报"、"多少度"等关键词的消息
              示例："北京天气"、"杭州今天天气怎么样"、"明天温度"、"介绍一下杭州的天气"
            - IMAGE_GENERATION：包含"生成"、"画"、"创作"、"制作"、"设计"等意图的消息
              示例："生成一张猫的图片"、"帮我画个风景"、"创作卡通头像"
            - IMAGE_EDIT：包含"修改"、"编辑"、"换"、"改成"、"换成"、"风格"、"转换"、"添加"、"去除"、"增强"、"修复"等意图的消息，用于在已有图片基础上进行编辑
              示例："换成水彩风格"、"在这张图片上加一只小猫"、"把背景换成蓝色"、"增强对比度"、"修复图片"
            - TEXT_CHAT：不满足以上条件的普通聊天消息
            
            content字段规则：
            - WEATHER: 提取城市名称，移除天气相关关键词
              示例输入"介绍一下杭州的天气" → content="杭州"
              示例输入"北京今天天气怎么样" → content="北京"
            - IMAGE_GENERATION: 提取图片描述内容，移除生成相关关键词
            - IMAGE_EDIT: 提取编辑指令内容，移除编辑相关关键词
            - TEXT_CHAT: 保持原消息不变
            
            请严格返回以下JSON格式（不要包含任何额外文字）：
            {
              "routeType": "TEXT_CHAT",
              "content": "处理后的内容",
              "needTts": false,
              "style": null
            }
            
            注意：
            - routeType只能是"WEATHER"、"IMAGE_GENERATION"、"IMAGE_EDIT"、"TEXT_CHAT"之一
            - needTts只有在用户明确要求语音回复时为true，否则为false
            - style只在IMAGE_GENERATION时填写，其他情况为null
            """;

        String result = llmService.chat(userMessage, systemPrompt);
        logger.info("AI routing result for message '{}': {}", userMessage, result);

        AiRouteResult aiResult = parseAiRouteResult(result);
        
        String style = aiResult.getStyle();
        if (style == null && RouteType.IMAGE_GENERATION.name().equals(aiResult.getRouteType())) {
            style = extractStyleFromMessage(userMessage);
        }

        RouteType routeType;
        try {
            routeType = RouteType.valueOf(aiResult.getRouteType());
        } catch (IllegalArgumentException e) {
            routeType = RouteType.TEXT_CHAT;
        }

        return new RouteResult(
            routeType,
            aiResult.getContent(),
            style,
            null,
            null,
            aiResult.isNeedTts()
        );
    }

    private AiRouteResult parseAiRouteResult(String json) {
        try {
            json = json.trim();
            if (json.startsWith("```")) {
                int endIndex = json.lastIndexOf("```");
                if (endIndex > 3) {
                    json = json.substring(3, endIndex).trim();
                }
            }
            
            JSONObject jsonObj = JSON.parseObject(json);
            AiRouteResult result = new AiRouteResult();
            
            String routeTypeStr = jsonObj.getString("routeType");
            if (routeTypeStr != null) {
                try {
                    RouteType.valueOf(routeTypeStr);
                    result.setRouteType(routeTypeStr);
                } catch (IllegalArgumentException e) {
                    logger.warn("Unknown route type: {}, defaulting to TEXT_CHAT", routeTypeStr);
                    result.setRouteType(RouteType.TEXT_CHAT.name());
                }
            } else {
                result.setRouteType(RouteType.TEXT_CHAT.name());
            }
            
            result.setContent(jsonObj.getString("content"));
            result.setNeedTts(jsonObj.getBooleanValue("needTts"));
            result.setStyle(jsonObj.getString("style"));
            
            return result;
        } catch (Exception e) {
            logger.warn("Failed to parse AI route result: {}, error: {}", json, e.getMessage());
            return new AiRouteResult(RouteType.TEXT_CHAT, json, false);
        }
    }

    private RouteResult keywordRouteTextMessage(String userMessage) {
        boolean needTts = isTtsRequest(userMessage);
        String messageWithoutTtsKeyword = needTts ? removeTtsKeywords(userMessage) : userMessage;

        if (isImageGenerationRequest(messageWithoutTtsKeyword)) {
            String style = extractStyleFromMessage(messageWithoutTtsKeyword);
            return new RouteResult(RouteType.IMAGE_GENERATION, messageWithoutTtsKeyword, style, null, null, needTts);
        }
        if (isImageEditRequest(messageWithoutTtsKeyword)) {
            return new RouteResult(RouteType.IMAGE_EDIT, messageWithoutTtsKeyword, null, null, null, needTts);
        }
        if (isWeatherRequest(messageWithoutTtsKeyword)) {
            String city = WeatherTool.extractCity(messageWithoutTtsKeyword);
            if (city == null) {
                city = "北京";
            }
            return new RouteResult(RouteType.WEATHER, city, null, null, null, needTts);
        }
        return new RouteResult(RouteType.TEXT_CHAT, messageWithoutTtsKeyword, null, null, null, needTts);
    }

    private boolean isImageEditRequest(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        for (String keyword : EDIT_KEYWORDS) {
            if (lowerMessage.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    public RouteResult routeImageMessage(String imageUrl) {
        return new RouteResult(RouteType.IMAGE_ANALYSIS, null, null, null, imageUrl);
    }

    public String process(RouteResult routeResult) throws Exception {
        return process(routeResult, null);
    }

    public String process(RouteResult routeResult, String conversationId) throws Exception {
        switch (routeResult.getRouteType()) {
            case TEXT_CHAT:
                if (conversationId != null && !conversationId.isEmpty()) {
                    return llmService.chatWithMemory(conversationId, routeResult.getTextContent());
                }
                return llmService.chat(routeResult.getTextContent());
            
            case IMAGE_ANALYSIS:
                ImageAnalysisResponse analysis;
                if (routeResult.getImageBytes() != null) {
                    analysis = visionService.analyzeImage(routeResult.getImageBytes());
                } else {
                    analysis = visionService.analyzeImageWithUrl(routeResult.getImageUrl());
                }
                return formatAnalysisResult(analysis);
            
            case IMAGE_GENERATION:
                String style = routeResult.getStyle();
                if (style == null || style.isEmpty()) {
                    style = config.getImage().getDefaultStyle();
                }
                String prompt = extractPromptFromMessage(routeResult.getTextContent(), style);
                ImageAnalysisResponse emptyAnalysis = new ImageAnalysisResponse();
                emptyAnalysis.setTitle("根据描述生成图片");
                emptyAnalysis.setDescription(prompt);
                String imageUrl = imageGenerationService.generateImage(emptyAnalysis, style);
                return "图片生成成功！链接：" + imageUrl;
            
            case WEATHER:
                String city = routeResult.getTextContent();
                if (city == null || city.trim().isEmpty()) {
                    city = "北京";
                }
                logger.info("Calling weather tool for city: {}", city);
                return weatherTool.execute(city);
            
            default:
                return "抱歉，无法识别消息类型";
        }
    }

    public String processImageWithGeneration(byte[] imageBytes, String style) throws Exception {
        ImageAnalysisResponse analysis = visionService.analyzeImage(imageBytes);
        return imageGenerationService.generateImage(analysis, style);
    }

    public String processFileQA(byte[] fileContent, String fileName, String question, String conversationId) throws Exception {
        logger.info("Processing file QA: fileName={}, question={}, fileSize={}, conversationId={}", fileName, question, fileContent.length, conversationId);

        if (!fileParserService.supports(fileName)) {
            logger.warn("File type not supported: {}", fileName);
            return "抱歉，不支持该文件类型。支持的格式：" + String.join(", ", fileParserService.getSupportedExtensions());
        }

        try {
            String fileText = fileParserService.parseBytes(fileContent, fileName);

            logger.info("File parsed successfully, content length: {}", fileText.length());

            String systemPrompt = "你是一个专业的文件分析助手。请根据用户上传的文件内容，准确回答用户的问题。如果文件内容中没有相关信息，请明确说明。";
            String prompt = "以下是文件内容：\n" + fileText + "\n\n请根据以上内容回答问题：" + question;

            String reply = llmService.chat(prompt, systemPrompt);

            if (conversationId != null) {
                chatMemoryService.saveMessagePair(conversationId, "用户上传了文件" + fileName + "并提问：" + question, reply);
            }

            return reply;

        } catch (Exception e) {
            logger.error("Failed to process file QA", e);
            return "处理文件时发生错误：" + e.getMessage();
        }
    }

    private boolean isImageGenerationRequest(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        for (String keyword : GENERATION_KEYWORDS) {
            if (lowerMessage.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String extractStyleFromMessage(String message) {
        String[] styleKeywords = {"赛博朋克", "写实", "卡通", "动漫", "水彩", "油画", "素描", "插画", "复古", "未来", "科幻"};
        for (String style : styleKeywords) {
            if (message.contains(style)) {
                return style + "风格";
            }
        }
        return null;
    }

    private String extractPromptFromMessage(String message, String style) {
        if (message == null) {
            return "一张" + style + "风格的图片";
        }
        String prompt = message;
        for (String keyword : GENERATION_KEYWORDS) {
            prompt = prompt.replace(keyword, "").trim();
        }
        if (prompt.isEmpty()) {
            prompt = "一张" + style + "风格的图片";
        }
        return prompt + "，" + style;
    }

    private boolean isWeatherRequest(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        for (String keyword : WEATHER_KEYWORDS) {
            if (lowerMessage.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private boolean isTtsRequest(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        String lowerMessage = message.toLowerCase();
        for (String keyword : TTS_KEYWORDS) {
            if (lowerMessage.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private String removeTtsKeywords(String message) {
        if (message == null) {
            return null;
        }
        String result = message;
        for (String keyword : TTS_KEYWORDS) {
            result = result.replace(keyword, "").trim();
        }
        return result;
    }

    

    private String formatAnalysisResult(ImageAnalysisResponse analysis) {
        StringBuilder sb = new StringBuilder();
        
        if (analysis.getTitle() != null && !analysis.getTitle().isEmpty()) {
            sb.append("标题：").append(analysis.getTitle()).append("\n");
        }
        if (analysis.getDescription() != null && !analysis.getDescription().isEmpty()) {
            sb.append("描述：").append(analysis.getDescription()).append("\n");
        }
        if (analysis.getScene() != null && !analysis.getScene().isEmpty()) {
            sb.append("场景：").append(analysis.getScene()).append("\n");
        }
        if (analysis.getEmotion() != null && !analysis.getEmotion().isEmpty()) {
            sb.append("情感：").append(analysis.getEmotion()).append("\n");
        }
        if (analysis.getObjects() != null && !analysis.getObjects().isEmpty()) {
            sb.append("物体：").append(String.join("、", analysis.getObjects())).append("\n");
        }
        if (analysis.getTags() != null && !analysis.getTags().isEmpty()) {
            sb.append("标签：").append(String.join("、", analysis.getTags())).append("\n");
        }
        if (analysis.getText() != null && !analysis.getText().isEmpty()) {
            String text = analysis.getText();
            if (text.startsWith("{") && text.endsWith("}")) {
                try {
                    JSONObject jsonText = JSON.parseObject(text);
                    StringBuilder textBuilder = new StringBuilder();
                    for (String key : jsonText.keySet()) {
                        Object value = jsonText.get(key);
                        textBuilder.append(key).append("：").append(value).append("；");
                    }
                    text = textBuilder.toString();
                    if (text.endsWith("；")) {
                        text = text.substring(0, text.length() - 1);
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse text field as JSON", e);
                }
            }
            sb.append("文字：").append(text).append("\n");
        }
        
        return sb.toString().trim();
    }

    public enum RouteType {
        TEXT_CHAT,
        IMAGE_ANALYSIS,
        IMAGE_GENERATION,
        IMAGE_EDIT,
        WEATHER
    }

    public static class RouteResult {
        private final RouteType routeType;
        private final String textContent;
        private final String style;
        private final byte[] imageBytes;
        private final String imageUrl;
        private final boolean needTts;

        public RouteResult(RouteType routeType, String textContent, String style, byte[] imageBytes, String imageUrl) {
            this(routeType, textContent, style, imageBytes, imageUrl, false);
        }

        public RouteResult(RouteType routeType, String textContent, String style, byte[] imageBytes, String imageUrl, boolean needTts) {
            this.routeType = routeType;
            this.textContent = textContent;
            this.style = style;
            this.imageBytes = imageBytes;
            this.imageUrl = imageUrl;
            this.needTts = needTts;
        }

        public RouteType getRouteType() {
            return routeType;
        }

        public String getTextContent() {
            return textContent;
        }

        public String getStyle() {
            return style;
        }

        public byte[] getImageBytes() {
            return imageBytes;
        }

        public String getImageUrl() {
            return imageUrl;
        }

        public boolean isNeedTts() {
            return needTts;
        }
    }
}