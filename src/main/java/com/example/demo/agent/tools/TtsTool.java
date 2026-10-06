package com.example.demo.agent.tools;

import com.alibaba.fastjson2.JSONObject;
import com.example.demo.ai.UserContextHolder;
import com.example.demo.gallery.MediaAssetService;
import com.example.demo.tts.XfTtsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Component
public class TtsTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(TtsTool.class);
    private static final String TOOL_NAME = "synthesizeSpeech";
    private static final String TOOL_DESCRIPTION = "将文本内容合成为语音，返回MP3音频文件";
    private static final String UPLOAD_DIR = "./uploads";

    private final XfTtsService ttsService;
    private final MediaAssetService mediaAssetService;
    private final ToolDefinition toolDefinition;

    public TtsTool(XfTtsService ttsService, MediaAssetService mediaAssetService) {
        this.ttsService = ttsService;
        this.mediaAssetService = mediaAssetService;
        this.toolDefinition = ToolDefinition.builder()
                .name(TOOL_NAME)
                .description(TOOL_DESCRIPTION)
                .parameter("text", "string", "待合成的文本内容")
                .required("text")
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
        String text = params.getString("text");
        
        if (text == null || text.trim().isEmpty()) {
            return ToolResult.failure("请提供需要合成语音的文本内容");
        }
        
        logger.info("Executing TTS tool for text (first 50 chars): {}", 
                text.length() > 50 ? text.substring(0, 50) + "..." : text);
        
        try {
            byte[] audioData = ttsService.synthesizeToMp3(text);
            String filePath = saveAudioToFile(audioData);
            logger.info("TTS synthesis completed, saved to: {}", filePath);
            
            return ToolResult.success(filePath);
        } catch (Exception e) {
            logger.error("TTS synthesis failed", e);
            return ToolResult.failure("语音合成失败，可能是网络问题或文本内容过长");
        }
    }

    private String saveAudioToFile(byte[] audioData) throws IOException {
        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        
        String fileName = "tts_" + UUID.randomUUID().toString() + ".mp3";
        Path filePath = uploadPath.resolve(fileName);
        
        try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
            fos.write(audioData);
        }

        mediaAssetService.record(UserContextHolder.getUserId(), fileName, "audio", "tts");
        
        return filePath.toAbsolutePath().toString();
    }
}
