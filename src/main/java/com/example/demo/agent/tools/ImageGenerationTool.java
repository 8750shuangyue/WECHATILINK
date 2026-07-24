package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.imagegen.ImageGenerationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ImageGenerationTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationTool.class);
    private static final String TOOL_NAME = "generateImage";
    private static final String TOOL_DESCRIPTION = "根据文本描述生成图片，支持多种风格转换";

    private final ImageGenerationService imageGenerationService;
    private final ToolDefinition toolDefinition;

    public ImageGenerationTool(ImageGenerationService imageGenerationService) {
        this.imageGenerationService = imageGenerationService;
        this.toolDefinition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .parameter("prompt", "string", "图片内容描述，详细描述您想要生成的图片内容")
                .parameter("style", "string", "图片风格，如：水彩、油画、素描、卡通、写实等（可选）")
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
        String style = params.getString("style");
        
        if (prompt == null || prompt.trim().isEmpty()) {
            return ToolResult.failure("请提供图片内容描述");
        }
        
        logger.info("Executing image generation tool, prompt: {}", 
                prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt);
        
        try {
            com.example.demo.vision.ImageAnalysisResponse analysis = 
                    new com.example.demo.vision.ImageAnalysisResponse();
            analysis.setDescription(prompt);
            
            String filePath = imageGenerationService.generateImage(analysis, style);
            logger.info("Image generation completed, saved to: {}", filePath);
            
            return ToolResult.success(filePath);
        } catch (Exception e) {
            logger.error("Image generation failed", e);
            return ToolResult.failure("图片生成失败，可能是网络问题或描述内容过长");
        }
    }
}