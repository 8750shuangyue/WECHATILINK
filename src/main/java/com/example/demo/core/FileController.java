package com.example.demo.core;

import com.example.demo.audit.AuditAction;
import com.example.demo.audit.OperationAuditService;
import com.example.demo.chat.LlmService;
import com.example.demo.ai.UserContextHolder;
import com.example.demo.config.DashScopeConfig;
import com.example.demo.gallery.MediaAssetService;
import com.example.demo.imagegen.ImageGenerationService;
import com.example.demo.vision.ImageAnalysisResponse;
import com.example.demo.vision.VisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/file")
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    private final VisionService visionService;
    private final ImageGenerationService imageGenerationService;
    private final FileStorageService fileStorageService;
    private final FileParserService fileParserService;
    private final FileUploadValidator fileUploadValidator;
    private final LlmService llmService;
    private final DashScopeConfig config;
    private final MediaAssetService mediaAssetService;
    private final OperationAuditService auditService;

    private static final List<String> TEXT_EXTENSIONS = Arrays.asList("txt", "md", "json");
    private static final List<String> IMAGE_EXTENSIONS = Arrays.asList("jpg", "jpeg", "png", "webp", "gif", "bmp", "tiff");

    @Value("${app.file-qa.max-prompt-chars:30000}")
    private int maxFileQaPromptChars = 30_000;

    @Value("${app.file-qa.max-question-chars:1000}")
    private int maxQuestionChars = 1000;

    @Value("${app.file-process.max-prompt-chars:4000}")
    private int maxProcessPromptChars = 4000;

    @Value("${app.http.image.max-download-bytes:20971520}")
    private long maxDownloadBytes = 20L * 1024 * 1024;

    public FileController(VisionService visionService, ImageGenerationService imageGenerationService,
                          FileStorageService fileStorageService, FileParserService fileParserService,
                          FileUploadValidator fileUploadValidator, LlmService llmService, DashScopeConfig config,
                          MediaAssetService mediaAssetService, OperationAuditService auditService) {
        this.visionService = visionService;
        this.imageGenerationService = imageGenerationService;
        this.fileStorageService = fileStorageService;
        this.fileParserService = fileParserService;
        this.fileUploadValidator = fileUploadValidator;
        this.llmService = llmService;
        this.config = config;
        this.mediaAssetService = mediaAssetService;
        this.auditService = auditService;
    }

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "style", required = false) String style,
            HttpServletRequest servletRequest) {
        
        Map<String, Object> response = new HashMap<>();
        UserContextHolder.setUserId((String) servletRequest.getAttribute("userName"));
        
        try {
            if (file.isEmpty()) {
                response.put("error", "文件不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            String filename = file.getOriginalFilename();
            if (filename == null || filename.isEmpty()) {
                response.put("error", "文件名不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            String extension = getFileExtension(filename).toLowerCase();
            logger.info("Processing file: {}, extension: {}", filename, extension);

            if (TEXT_EXTENSIONS.contains(extension)) {
                fileUploadValidator.validateText(file);
                ResponseEntity<Map<String, Object>> result = processTextFile(file, style);
                auditService.record(servletRequest, AuditAction.UPLOAD_FILE_PROCESS, AuditAction.SUCCESS,
                        "file", filename, "ext=" + extension + ", bytes=" + file.getSize());
                return result;
            } else if (IMAGE_EXTENSIONS.contains(extension)) {
                fileUploadValidator.validateImage(file);
                ResponseEntity<Map<String, Object>> result = processImageFile(file, style);
                auditService.record(servletRequest, AuditAction.UPLOAD_FILE_PROCESS, AuditAction.SUCCESS,
                        "file", filename, "ext=" + extension + ", bytes=" + file.getSize());
                return result;
            } else {
                response.put("error", "不支持的文件类型");
                response.put("supported_types", "文本文件: " + TEXT_EXTENSIONS + ", 图片文件: " + IMAGE_EXTENSIONS);
                return ResponseEntity.badRequest().body(response);
            }

        } catch (FileValidationException e) {
            logger.warn("Rejected file upload: {}", e.getMessage());
            auditService.record(servletRequest, AuditAction.UPLOAD_FILE_PROCESS, AuditAction.FAILURE,
                    "file", file.getOriginalFilename(), e.getMessage());
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            logger.error("Error processing file", e);
            auditService.record(servletRequest, AuditAction.UPLOAD_FILE_PROCESS, AuditAction.FAILURE,
                    "file", file.getOriginalFilename(), e.getClass().getSimpleName());
            response.put("error", "处理文件时发生错误: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } finally {
            UserContextHolder.clear();
        }
    }

    private ResponseEntity<Map<String, Object>> processTextFile(MultipartFile file, String style) throws Exception {
        Map<String, Object> response = new HashMap<>();
        
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        boolean truncated = content.length() > maxProcessPromptChars;
        if (truncated) {
            content = content.substring(0, maxProcessPromptChars);
        }
        logger.info("Text file content length: {}, truncated: {}", content.length(), truncated);

        String targetStyle = style != null && !style.trim().isEmpty() ? style : config.getImage().getDefaultStyle();
        
        ImageAnalysisResponse analysis = new ImageAnalysisResponse();
        analysis.setTitle("根据文本提示生成图片");
        analysis.setDescription(content + "，风格：" + targetStyle);
        
        String imageUrl = imageGenerationService.generateImage(analysis, targetStyle);
        byte[] imageBytes = downloadImage(imageUrl);
        String mimeType = detectImageMimeType(imageBytes);
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        response.put("success", true);
        response.put("type", "image_generation");
        response.put("prompt", content);
        response.put("truncated", truncated);
        response.put("style", targetStyle);
        response.put("generatedImageUrl", imageUrl);
        response.put("imageData", "data:" + mimeType + ";base64," + base64Image);
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/qa")
    public ResponseEntity<Map<String, Object>> fileQA(
            @RequestParam("file") MultipartFile file,
            @RequestParam("question") String question,
            HttpServletRequest servletRequest) {

        Map<String, Object> response = new HashMap<>();
        
        try {
            if (file.isEmpty()) {
                response.put("error", "文件不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            String filename = file.getOriginalFilename();
            if (filename == null || filename.isEmpty()) {
                response.put("error", "文件名不能为空");
                return ResponseEntity.badRequest().body(response);
            }

            if (!fileParserService.supports(filename)) {
                response.put("error", "不支持的文件类型");
                response.put("supported_types", fileParserService.getSupportedExtensions());
                return ResponseEntity.badRequest().body(response);
            }

            if (question == null || question.trim().isEmpty()) {
                response.put("error", "问题不能为空");
                return ResponseEntity.badRequest().body(response);
            }
            if (question.length() > maxQuestionChars) {
                response.put("error", "问题过长（最多 " + maxQuestionChars + " 字符）");
                return ResponseEntity.badRequest().body(response);
            }

            fileUploadValidator.validateDocument(file);
            logger.info("Processing file QA: {}, question length: {}", filename, question.length());

            String fileContent = fileParserService.parseFile(file);
            boolean truncated = fileContent.length() > maxFileQaPromptChars;
            String promptContent = truncated
                    ? fileContent.substring(0, maxFileQaPromptChars)
                    : fileContent;
            logger.info("File content length: {}, truncated: {}", fileContent.length(), truncated);

            String systemPrompt = "你是一个专业的文件分析助手。请根据用户上传的文件内容，准确回答用户的问题。如果文件内容中没有相关信息，请明确说明。";
            String prompt = "以下是文件内容：\n" + promptContent + "\n\n请根据以上内容回答问题：" + question;

            String answer = llmService.chat(prompt, systemPrompt);

            auditService.record(servletRequest, AuditAction.UPLOAD_FILE_QA, AuditAction.SUCCESS,
                    "file", filename, "promptChars=" + promptContent.length() + ", truncated=" + truncated);
            response.put("success", true);
            response.put("type", "file_qa");
            response.put("filename", filename);
            response.put("content_length", fileContent.length());
            response.put("prompt_chars", promptContent.length());
            response.put("truncated", truncated);
            response.put("question", question);
            response.put("answer", answer);

            return ResponseEntity.ok(response);

        } catch (FileValidationException e) {
            logger.warn("Rejected file QA upload: {}", e.getMessage());
            auditService.record(servletRequest, AuditAction.UPLOAD_FILE_QA, AuditAction.FAILURE,
                    "file", file.getOriginalFilename(), e.getMessage());
            response.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(response);
        } catch (Exception e) {
            logger.error("Error processing file QA", e);
            auditService.record(servletRequest, AuditAction.UPLOAD_FILE_QA, AuditAction.FAILURE,
                    "file", file.getOriginalFilename(), e.getClass().getSimpleName());
            response.put("error", "处理文件问答时发生错误: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private ResponseEntity<Map<String, Object>> processImageFile(MultipartFile file, String style) throws Exception {
        Map<String, Object> response = new HashMap<>();
        
        byte[] uploadedBytes = file.getBytes();
        String extension = getFileExtension(file.getOriginalFilename());
        String imageUrl = fileStorageService.storeImage(uploadedBytes, extension);
        String storedFileName = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);
        mediaAssetService.record(UserContextHolder.getUserId(), storedFileName, "image", "uploaded");

        // 直接发送图片字节，避免把站内相对路径交给外部视觉 API
        ImageAnalysisResponse analysis = visionService.analyzeImage(uploadedBytes, mimeForExtension(extension));
        
        response.put("success", true);
        response.put("type", "image_analysis");
        response.put("analysis", analysis);
        
        if (style != null && !style.trim().isEmpty()) {
            String generatedImageUrl = imageGenerationService.generateImage(analysis, style);
            byte[] imageBytes = downloadImage(generatedImageUrl);
            String mimeType = detectImageMimeType(imageBytes);
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            response.put("generatedImageUrl", generatedImageUrl);
            response.put("imageData", "data:" + mimeType + ";base64," + base64Image);
        }
        
        return ResponseEntity.ok(response);
    }

    private byte[] downloadImage(String imageUrl) throws Exception {
        // Handle local file paths (both "file:///..." and "C:\..." style)
        if (imageUrl.startsWith("file:/") || (imageUrl.length() >= 2 && imageUrl.charAt(1) == ':')) {
            java.nio.file.Path path = imageUrl.startsWith("file:/")
                ? java.nio.file.Paths.get(java.net.URI.create(imageUrl))
                : java.nio.file.Paths.get(imageUrl);
            if (java.nio.file.Files.size(path) > maxDownloadBytes) {
                throw new IOException("生成的图片超过下载上限");
            }
            return java.nio.file.Files.readAllBytes(path);
        }
        java.net.URL url = new java.net.URL(imageUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new java.io.IOException("Image download failed with status: " + status);
        }
        try (InputStream is = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = is.read(buffer)) != -1) {
                total += read;
                if (total > maxDownloadBytes) {
                    throw new IOException("生成的图片超过下载上限");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private String mimeForExtension(String extension) {
        String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "bmp" -> "image/bmp";
            case "webp" -> "image/webp";
            case "tif", "tiff" -> "image/tiff";
            default -> "image/jpeg";
        };
    }

    private String detectImageMimeType(byte[] imageBytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes);
             ImageInputStream iis = ImageIO.createImageInputStream(bis)) {
            Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(iis);
            if (readers.hasNext()) {
                String[] formats = readers.next().getFormatName().split(",");
                for (String fmt : formats) {
                    fmt = fmt.trim().toLowerCase();
                    if ("jpeg".equals(fmt) || "jpg".equals(fmt)) return "image/jpeg";
                    if ("png".equals(fmt)) return "image/png";
                    if ("gif".equals(fmt)) return "image/gif";
                    if ("bmp".equals(fmt)) return "image/bmp";
                    if ("wbmp".equals(fmt)) return "image/vnd.wap.wbmp";
                }
            }
        } catch (Exception e) {
            logger.debug("Could not detect image format via ImageIO", e);
        }
        // Fallback: magic bytes
        if (imageBytes.length >= 2 && (imageBytes[0] & 0xff) == 0xff && imageBytes[1] == (byte)0xd8) return "image/jpeg";
        if (imageBytes.length >= 4 && imageBytes[0] == (byte)0x89 && imageBytes[1] == 'P' && imageBytes[2] == 'N' && imageBytes[3] == 'G') return "image/png";
        if (imageBytes.length >= 4 && imageBytes[0] == 'G' && imageBytes[1] == 'I' && imageBytes[2] == 'F') return "image/gif";
        if (imageBytes.length >= 2 && imageBytes[0] == 'B' && imageBytes[1] == 'M') return "image/bmp";
        return "image/png";
    }

    private String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
}
