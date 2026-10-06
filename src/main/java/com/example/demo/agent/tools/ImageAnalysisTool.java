package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.chat.UserSession;
import com.example.demo.chat.UserSessionService;
import com.example.demo.vision.VisionService;
import org.springframework.stereotype.Component;

@Component
public class ImageAnalysisTool extends BaseTool {

    private static final String TOOL_NAME = "analyzeImage";
    private static final String TOOL_DESCRIPTION = "分析用户上传的图片内容，提取图片中的文字、描述图片场景、识别物体等。当用户发送图片后询问图片内容、要求分析图片、提取图片文字、描述图片场景时调用此工具。";

    private final VisionService visionService;
    private final UserSessionService userSessionService;
    private final ToolDefinition toolDefinition;

    public ImageAnalysisTool(VisionService visionService, UserSessionService userSessionService) {
        this.visionService = visionService;
        this.userSessionService = userSessionService;
        this.toolDefinition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .parameter("userId", "string", "用户ID，用于获取之前上传的图片（由系统自动填充）")
                .parameter("prompt", "string", "分析指令，如：描述图片内容、提取图片中的文字、识别图片中的物体等（可选，默认描述图片内容）")
                .required("userId")
                .build();
    }

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return TOOL_DESCRIPTION;
    }

    @Override
    public ToolDefinition getDefinition() {
        return toolDefinition;
    }

    @Override
    public ToolResult<String> execute(JSONObject params) {
        String userId = params.getString("userId");
        String prompt = params.getString("prompt");
        
        if (userId == null || userId.trim().isEmpty()) {
            return ToolResult.failure("无法获取用户信息，请重新发送图片后再试");
        }
        
        if (!userSessionService.hasPendingImage(userId)) {
            return ToolResult.failure("请先发送一张图片，然后再发送分析指令");
        }
        
        if (prompt == null || prompt.trim().isEmpty()) {
            prompt = "请描述这张图片的内容";
        }
        
        logger.info("Executing image analysis tool, userId: {}, prompt: {}", userId, 
                prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt);
        
        try {
            String pendingImageBase64 = userSessionService.getPendingImageBase64(userId);
            String analysis = visionService.analyzeImageWithCustomPrompt(
                    "data:image/jpeg;base64," + pendingImageBase64, prompt);
            
            logger.info("Image analysis completed, result length: {} chars", analysis.length());
            
            if (userId != null) {
                userSessionService.markImageAsAnalyzed(userId);
                UserSession session = userSessionService.getSession(userId);
                if (session != null) {
                    session.setImageDescription(analysis);
                    userSessionService.saveSession(session);
                }
            }
            
            return ToolResult.success(analysis);
        } catch (Exception e) {
            logger.error("Image analysis failed", e);
            return ToolResult.failure("图片分析失败，可能是网络问题或图片格式不支持");
        }
    }
}