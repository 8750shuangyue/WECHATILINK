package com.example.demo.asr;

import com.example.demo.audit.AuditAction;
import com.example.demo.audit.OperationAuditService;
import com.example.demo.core.FileUploadValidator;
import com.example.demo.core.FileValidationException;
import jakarta.servlet.http.HttpServletRequest;
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
    private final FileUploadValidator fileUploadValidator;
    private final OperationAuditService auditService;

    public AsrController(AudioConverterService audioConverterService,
                         DashScopeAsrService asrService,
                         FileUploadValidator fileUploadValidator,
                         OperationAuditService auditService) {
        this.audioConverterService = audioConverterService;
        this.asrService = asrService;
        this.fileUploadValidator = fileUploadValidator;
        this.auditService = auditService;
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> transcribe(@RequestParam("file") MultipartFile file,
                                                          HttpServletRequest request) {
        Map<String, Object> r = new HashMap<>();
        String fileName = file == null ? null : file.getOriginalFilename();
        try {
            if (file == null || file.isEmpty()) {
                auditService.record(request, AuditAction.UPLOAD_ASR, AuditAction.FAILURE,
                        "audio", fileName, "empty file");
                r.put("success", false);
                r.put("error", "音频文件为空");
                return ResponseEntity.badRequest().body(r);
            }

            String ext = fileUploadValidator.validateAudio(file);

            byte[] wav = audioConverterService.convertAnyToWav16k16bitMono(file.getBytes(), ext);
            String text = asrService.recognize(wav);

            auditService.record(request, AuditAction.UPLOAD_ASR, AuditAction.SUCCESS,
                    "audio", fileName, "ext=" + ext + ", bytes=" + file.getSize());
            r.put("success", true);
            r.put("text", text == null ? "" : text.trim());
        } catch (FileValidationException e) {
            log.warn("[ASR] Upload rejected: {}", e.getMessage());
            auditService.record(request, AuditAction.UPLOAD_ASR, AuditAction.FAILURE,
                    "audio", fileName, e.getMessage());
            r.put("success", false);
            r.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(r);
        } catch (Exception e) {
            log.error("[ASR] transcribe failed", e);
            auditService.record(request, AuditAction.UPLOAD_ASR, AuditAction.FAILURE,
                    "audio", fileName, e.getClass().getSimpleName());
            r.put("success", false);
            r.put("error", e.getMessage());
        }
        return ResponseEntity.ok(r);
    }
}
