package com.example.demo.core;

import com.example.demo.chat.LlmService;
import com.example.demo.config.DashScopeConfig;
import com.example.demo.imagegen.ImageGenerationService;
import com.example.demo.vision.ImageAnalysisResponse;
import com.example.demo.vision.VisionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/file")
public class FileController {

    private static final Logger logger = LoggerFactory.getLogger(FileController.class);

    private final VisionService visionService;
    private final ImageGenerationService imageGenerationService;
    private final FileStorageService fileStorageService;
    private final FileParserService fileParserService;
    private final LlmService llmService;
    private final DashScopeConfig config;

    private static final List<String> TEXT_EXTENSIONS = Arrays.asList("txt", "md", "json");
    private static final List<String> IMAGE_EXTENSIONS = Arrays.asList("jpg", "jpeg", "png", "webp", "gif", "bmp", "tiff");

    public FileController(VisionService visionService, ImageGenerationService imageGenerationService,
                          FileStorageService fileStorageService, FileParserService fileParserService,
                          LlmService llmService, DashScopeConfig config) {
        this.visionService = visionService;
        this.imageGenerationService = imageGenerationService;
        this.fileStorageService = fileStorageService;
        this.fileParserService = fileParserService;
        this.llmService = llmService;
        this.config = config;
    }

    @PostMapping("/process")
    public ResponseEntity<Map<String, Object>> processFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "style", required = false) String style) {
        
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

            String extension = getFileExtension(filename).toLowerCase();
            logger.info("Processing file: {}, extension: {}", filename, extension);

            if (TEXT_EXTENSIONS.contains(extension)) {
                return processTextFile(file, style);
            } else if (IMAGE_EXTENSIONS.contains(extension)) {
                return processImageFile(file, style);
            } else {
                response.put("error", "不支持的文件类型");
                response.put("supported_types", "文本文件: " + TEXT_EXTENSIONS + ", 图片文件: " + IMAGE_EXTENSIONS);
                return ResponseEntity.badRequest().body(response);
            }

        } catch (Exception e) {
            logger.error("Error processing file", e);
            response.put("error", "处理文件时发生错误: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private ResponseEntity<Map<String, Object>> processTextFile(MultipartFile file, String style) throws Exception {
        Map<String, Object> response = new HashMap<>();
        
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);
        logger.info("Text file content length: {}", content.length());

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
        response.put("style", targetStyle);
        response.put("generatedImageUrl", imageUrl);
        response.put("imageData", "data:" + mimeType + ";base64," + base64Image);
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/qa")
    public ResponseEntity<Map<String, Object>> fileQA(
            @RequestParam("file") MultipartFile file,
            @RequestParam("question") String question) {
        
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

            logger.info("Processing file QA: {}, question: {}", filename, question);

            String fileContent = fileParserService.parseFile(file);
            logger.info("File content length: {}", fileContent.length());

            String systemPrompt = "你是一个专业的文件分析助手。请根据用户上传的文件内容，准确回答用户的问题。如果文件内容中没有相关信息，请明确说明。";
            String prompt = "以下是文件内容：\n" + fileContent + "\n\n请根据以上内容回答问题：" + question;

            String answer = llmService.chat(prompt, systemPrompt);

            response.put("success", true);
            response.put("type", "file_qa");
            response.put("filename", filename);
            response.put("content_length", fileContent.length());
            response.put("question", question);
            response.put("answer", answer);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error processing file QA", e);
            response.put("error", "处理文件问答时发生错误: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private ResponseEntity<Map<String, Object>> processImageFile(MultipartFile file, String style) throws Exception {
        Map<String, Object> response = new HashMap<>();
        
        String extension = getFileExtension(file.getOriginalFilename());
        String imageUrl = fileStorageService.storeImage(file.getBytes(), extension);
        
        ImageAnalysisResponse analysis = visionService.analyzeImageWithUrl(imageUrl);
        
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
            return java.nio.file.Files.readAllBytes(path);
        }
        java.net.URL url = new java.net.URL(imageUrl);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        int status = conn.getResponseCode();
        if (status != 200) {
            throw new java.io.IOException("Image download failed with status: " + status);
        }
        try (java.io.InputStream is = conn.getInputStream()) {
            return is.readAllBytes();
        }
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