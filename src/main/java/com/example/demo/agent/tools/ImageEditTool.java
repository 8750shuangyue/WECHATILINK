package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.imagegen.ImageGenerationService;
import com.example.demo.chat.UserSessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class ImageEditTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ImageEditTool.class);
    private static final String TOOL_NAME = "editImage";
    private static final String TOOL_DESCRIPTION = "用于编辑、修改、调整上下文中已存在的图片。当用户提到'这张图'、'上一张'、'刚才的图'，或要求对已有图片进行增删改、风格转换、添加元素时调用。特别适用于图生图场景：以用户上传的图片为背景/基础，在上面添加新内容、修改元素、改变风格等。";

    private final ImageGenerationService imageGenerationService;
    private final UserSessionService userSessionService;
    private final ToolDefinition toolDefinition;

    public ImageEditTool(ImageGenerationService imageGenerationService, 
                         UserSessionService userSessionService) {
        this.imageGenerationService = imageGenerationService;
        this.userSessionService = userSessionService;
        this.toolDefinition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .parameter("prompt", "string", "编辑指令，如：换成水彩风格、在图片上加一只小猫、改成黑白照片等")
                .parameter("userId", "string", "用户ID，用于获取之前上传的图片（由系统自动填充）")
                .required("prompt")
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
        String prompt = params.getString("prompt");
        if (prompt == null || prompt.trim().isEmpty()) {
            prompt = params.getString("description");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            prompt = params.getString("modification_instruction");
        }
        if (prompt == null || prompt.trim().isEmpty()) {
            prompt = params.getString("edit_instruction");
        }
        String userId = params.getString("userId");
        
        if (prompt == null || prompt.trim().isEmpty()) {
            return ToolResult.failure("请提供编辑指令");
        }
        
        if (userId == null || userId.trim().isEmpty()) {
            return ToolResult.failure("无法获取用户信息，请重新发送图片后再试");
        }
        
        if (!userSessionService.hasPendingImage(userId)) {
            logger.warn("No pending image found for user {}", userId);
            logger.warn("Session info - hasPendingImage: {}, hasPendingFile: {}", 
                    userSessionService.hasPendingImage(userId),
                    userSessionService.hasPendingFile(userId));
            return ToolResult.failure("请先发送一张图片，然后再发送编辑指令");
        }
        
        logger.info("Executing image edit tool, userId: {}, prompt: {}", userId, 
                prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt);
        
        try {
            String pendingImageBase64 = userSessionService.getPendingImageBase64(userId);
            logger.info("Pending image base64 length: {}, starts with data: {}", 
                    pendingImageBase64 != null ? pendingImageBase64.length() : 0,
                    pendingImageBase64 != null && pendingImageBase64.startsWith("data:"));
            
            if (pendingImageBase64 == null || pendingImageBase64.isEmpty()) {
                logger.error("Pending image base64 is null or empty for user {}", userId);
                return ToolResult.failure("图片数据为空，请重新发送图片");
            }
            
            String resultPath = imageGenerationService.editImage(pendingImageBase64, prompt);
            logger.info("Image edit completed, saved to: {}", resultPath);
            
            if (resultPath == null || resultPath.isEmpty()) {
                logger.error("Image generation service returned null or empty path");
                return ToolResult.failure("图片生成服务返回空结果");
            }
            
            return ToolResult.success(resultPath);
        } catch (IllegalArgumentException e) {
            logger.error("Image edit failed with invalid argument: {}", e.getMessage());
            return ToolResult.failure("图片编辑失败：" + e.getMessage());
        } catch (IOException e) {
            logger.error("Image edit failed with IO error: {}", e.getMessage());
            return ToolResult.failure("图片编辑失败，网络或文件操作错误");
        } catch (Exception e) {
            logger.error("Image edit failed", e);
            return ToolResult.failure("图片编辑失败：" + e.getMessage());
        }
    }
}