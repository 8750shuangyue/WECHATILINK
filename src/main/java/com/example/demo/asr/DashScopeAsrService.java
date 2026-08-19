package com.example.demo.asr;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.example.demo.config.DashScopeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class DashScopeAsrService {

    private static final Logger logger = LoggerFactory.getLogger(DashScopeAsrService.class);

    private final DashScopeConfig config;
    private Recognition recognizer;

    public DashScopeAsrService(DashScopeConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        recognizer = new Recognition();
        logger.info("DashScope ASR service initialized");
    }

    public String recognize(byte[] wavData) throws Exception {
        if (wavData == null || wavData.length == 0) {
            throw new IllegalArgumentException("音频数据不能为空");
        }

        logger.info("WAV data length: {} bytes", wavData.length);

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("asr_", ".wav");
            Files.write(tempFile, wavData);
            logger.info("Audio file saved to: {}", tempFile);

            // DashScope 语音识别需要百炼（DashScope）的 API Key，
            // 与 Embedding/视觉共用同一把 key；DeepSeek 的 key 不可用于 ASR。
            String asrApiKey = (config.getEmbedding() != null && config.getEmbedding().getApiKey() != null)
                    ? config.getEmbedding().getApiKey()
                    : config.getApiKey();

            RecognitionParam param = RecognitionParam.builder()
                    .apiKey(asrApiKey)
                    .model("fun-asr-realtime-2026-02-28")
                    .format("wav")
                    .sampleRate(16000)
                    .parameter("language_hints", new String[]{"zh", "en"})
                    .build();

            String result = recognizer.call(param, tempFile.toFile());
            logger.info("ASR recognition result: {}", result);

            return result;
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    logger.warn("Failed to delete temp file", e);
                }
            }
        }
    }
}
