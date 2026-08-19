package com.example.demo.asr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/**
 * Web 语音输入：上传浏览器录音（webm/opus 等）→ ffmpeg 转 WAV → DashScope ASR 识别 → 返回文本。
 */
@RestController
@RequestMapping("/api/asr")
public class AsrController {

    private static final Logger log = LoggerFactory.getLogger(AsrController.class);

    private final AudioConverterService audioConverterService;
    private final DashScopeAsrService asrService;

    public AsrController(AudioConverterService audioConverterService,
                         DashScopeAsrService asrService) {
        this.audioConverterService = audioConverterService;
        this.asrService = asrService;
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> transcribe(@RequestParam("file") MultipartFile file) {
        Map<String, Object> r = new HashMap<>();
        try {
            if (file == null || file.isEmpty()) {
                r.put("success", false);
                r.put("error", "音频文件为空");
                return ResponseEntity.badRequest().body(r);
            }

            String originalName = file.getOriginalFilename();
            String ext = "webm";
            if (originalName != null) {
                int idx = originalName.lastIndexOf('.');
                if (idx >= 0 && idx < originalName.length() - 1) {
                    ext = originalName.substring(idx + 1).toLowerCase();
                }
            }

            byte[] wav = audioConverterService.convertAnyToWav16k16bitMono(file.getBytes(), ext);
            String text = asrService.recognize(wav);

            r.put("success", true);
            r.put("text", text == null ? "" : text.trim());
        } catch (Exception e) {
            log.error("[ASR] transcribe failed", e);
            r.put("success", false);
            r.put("error", e.getMessage());
        }
        return ResponseEntity.ok(r);
    }
}
