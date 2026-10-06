package com.example.demo.imagegen;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.ai.UserContextHolder;
import com.example.demo.config.DashScopeConfig;
import com.example.demo.gallery.MediaAssetService;
import com.example.demo.vision.ImageAnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.UUID;

@Service
public class ImageGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationService.class);

    private final DashScopeConfig config;
    private final MediaAssetService mediaAssetService;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public ImageGenerationService(DashScopeConfig config, MediaAssetService mediaAssetService) {
        this.config = config;
        this.mediaAssetService = mediaAssetService;
    }

    private String imageApiKey() {
        String key = config.getImage().getApiKey();
        return (key != null && !key.isBlank()) ? key : config.getApiKey();
    }

    /**
     * @return local file path (e.g. "C:\\...\\uploads\\gen_abc.png")
     */
    public String generateImage(ImageAnalysisResponse analysis, String targetStyle) throws IOException, InterruptedException {
        String style = targetStyle != null && !targetStyle.trim().isEmpty() ? targetStyle : config.getImage().getDefaultStyle();

        String prompt = buildPrompt(analysis, style);

        JSONObject jsonRequest = new JSONObject();
        jsonRequest.put("model", config.getImage().getModel());
        JSONObject input = new JSONObject();
        JSONArray messages = new JSONArray();
        JSONObject msg = new JSONObject();
        msg.put("role", "user");
        JSONArray content = new JSONArray();
        JSONObject textContent = new JSONObject();
        textContent.put("text", prompt);
        content.add(textContent);
        msg.put("content", content);
        messages.add(msg);
        input.put("messages", messages);
        jsonRequest.put("input", input);
        JSONObject parameters = new JSONObject();
        parameters.put("size", config.getImage().getDefaultResolution());
        parameters.put("n", 1);
        jsonRequest.put("parameters", parameters);

        return executeImageApi(jsonRequest, "Image generation");
    }
    
    private String executeImageApi(JSONObject jsonRequest, String operationName) throws IOException, InterruptedException {
        String jsonStr = JSON.toJSONString(jsonRequest);
        logger.info("{} request: {}", operationName, jsonStr);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(config.getImage().getBaseUrl()))
            .header("Authorization", "Bearer " + imageApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonStr))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        String responseBody = response.body();

        logger.info("{} response status: {}, body: {}",
                operationName, response.statusCode(), responseBody);

        if (response.statusCode() != 200) {
            throw new IOException(operationName + " failed with status: " + response.statusCode() + ", body: " + responseBody);
        }

        JSONObject responseJson = JSON.parseObject(responseBody);

        JSONObject output = responseJson.getJSONObject("output");
        if (output == null) {
            throw new RuntimeException("Invalid " + operationName + " response: " + responseBody);
        }

        JSONArray choices = output.getJSONArray("choices");
        if (choices != null && !choices.isEmpty()) {
            JSONObject choice = choices.getJSONObject(0);
            JSONObject message = choice.getJSONObject("message");
            if (message != null) {
                JSONArray contentArr = message.getJSONArray("content");
                if (contentArr != null && !contentArr.isEmpty()) {
                    JSONObject contentItem = contentArr.getJSONObject(0);
                    String imageValue = contentItem.getString("image");
                    if (imageValue != null && !imageValue.isEmpty()) {
                        logger.info("Raw image value from API (first 100 chars): [{}]",
                                imageValue.length() > 100 ? imageValue.substring(0, 100) + "..." : imageValue);
                        return downloadAndSaveImage(imageValue);
                    }
                }
            }
        }

        throw new RuntimeException("Failed to get image URL from " + operationName + " response: " + responseBody);
    }

    /**
     * Downloads the image (from remote URL or decodes from base64) and saves it locally.
     * Always returns a local file path.
     */
    private String downloadAndSaveImage(String imageValue) throws IOException, InterruptedException {
        byte[] imageBytes;
        String extension;

        if (imageValue.startsWith("http://") || imageValue.startsWith("https://")) {
            // Remote URL — download with auth headers in case the MaaS CDN requires them
            String url = extractUrlFromText(imageValue);
            logger.info("Downloading generated image from: {}", url);
            HttpRequest downloadReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
            .header("Authorization", "Bearer " + imageApiKey())
                .GET()
                .build();
            HttpResponse<InputStream> downloadResp = httpClient.send(downloadReq, HttpResponse.BodyHandlers.ofInputStream());
            if (downloadResp.statusCode() != 200) {
                // Retry without auth (public OSS signed URL)
                logger.info("Auth download returned {}, retrying without auth", downloadResp.statusCode());
                HttpRequest retryReq = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
                downloadResp = httpClient.send(retryReq, HttpResponse.BodyHandlers.ofInputStream());
                if (downloadResp.statusCode() != 200) {
                    throw new IOException("Failed to download generated image, status: " + downloadResp.statusCode());
                }
            }
            try (InputStream is = downloadResp.body()) {
                imageBytes = is.readAllBytes();
            }
            // Detect format from URL or magic bytes
            extension = extractExtensionFromUrl(url);
        } else {
            // Base64 data
            String b64;
            if (imageValue.contains(";base64,")) {
                int commaIdx = imageValue.indexOf(";base64,") + 8;
                b64 = imageValue.substring(commaIdx);
            } else {
                b64 = imageValue;
            }
            imageBytes = Base64.getDecoder().decode(b64);
            extension = ".png";
        }

        // Use magic bytes to determine real extension
        String detectedExt = detectExtensionFromBytes(imageBytes);
        if (detectedExt != null) {
            extension = detectedExt;
        }

        Path uploadsPath = Paths.get("./uploads");
        if (!Files.exists(uploadsPath)) {
            Files.createDirectories(uploadsPath);
        }

        // Keep original PNG — JPEG conversion may produce incompatible format with WeChat

        String filename = "gen_" + UUID.randomUUID() + extension;
        Path targetPath = uploadsPath.resolve(filename);
        Files.write(targetPath, imageBytes);
        logger.info("Saved generated image to: {} ({} bytes)", targetPath.toAbsolutePath(), imageBytes.length);
        mediaAssetService.record(UserContextHolder.getUserId(), filename, "image", "generated");
        return targetPath.toAbsolutePath().toString();
    }

    private byte[] convertPngToJpeg(byte[] pngData) throws IOException {
        java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(pngData);
        java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(bis);
        if (img == null) return null;
        // Remove alpha channel: draw on white background
        java.awt.image.BufferedImage rgbImg = new java.awt.image.BufferedImage(
            img.getWidth(), img.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = rgbImg.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(rgbImg, "JPEG", bos);
        return bos.toByteArray();
    }

    private String extractUrlFromText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int startIdx = text.indexOf("https://");
        if (startIdx == -1) {
            startIdx = text.indexOf("http://");
        }
        if (startIdx == -1) {
            return text.trim();
        }
        int endIdx = text.indexOf(" ", startIdx);
        if (endIdx == -1) {
            endIdx = text.length();
        }
        return text.substring(startIdx, endIdx).trim();
    }

    private String extractExtensionFromUrl(String url) {
        if (url == null) return ".png";
        String path = url;
        int qIdx = path.indexOf('?');
        if (qIdx > 0) path = path.substring(0, qIdx);
        int dotIdx = path.lastIndexOf('.');
        if (dotIdx > 0 && dotIdx < path.length() - 1) {
            String ext = path.substring(dotIdx + 1).toLowerCase();
            if (ext.matches("^(png|jpe?g|gif|bmp|webp)$")) {
                return "." + ext.replace("jpeg", "jpg");
            }
        }
        return ".png";
    }

    private String detectExtensionFromBytes(byte[] data) {
        if (data == null || data.length < 4) return null;
        if ((data[0] & 0xff) == 0xff && data[1] == (byte)0xd8) return ".jpg";
        if (data[0] == (byte)0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') return ".png";
        if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return ".gif";
        if (data[0] == 'B' && data[1] == 'M') return ".bmp";
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') return ".webp";
        return null;
    }

    private String buildPrompt(ImageAnalysisResponse analysis, String style) {
        StringBuilder prompt = new StringBuilder();

        if (analysis.getTitle() != null && !analysis.getTitle().isEmpty()) {
            prompt.append(analysis.getTitle()).append("，");
        }

        if (analysis.getDescription() != null && !analysis.getDescription().isEmpty()) {
            prompt.append(analysis.getDescription()).append("，");
        }

        if (analysis.getObjects() != null && !analysis.getObjects().isEmpty()) {
            prompt.append("包含：").append(String.join("、", analysis.getObjects())).append("，");
        }

        if (analysis.getScene() != null && !analysis.getScene().isEmpty()) {
            prompt.append("场景：").append(analysis.getScene()).append("，");
        }

        if (analysis.getEmotion() != null && !analysis.getEmotion().isEmpty()) {
            prompt.append("情感：").append(analysis.getEmotion()).append("，");
        }

        if (analysis.getTags() != null && !analysis.getTags().isEmpty()) {
            prompt.append("标签：").append(String.join("、", analysis.getTags())).append("，");
        }

        prompt.append("风格：").append(style);

        return prompt.toString();
    }

    public String editImage(String imageBase64, String instruction) throws IOException, InterruptedException {
        JSONObject jsonRequest = new JSONObject();
        jsonRequest.put("model", config.getImage().getModel());

        JSONObject input = new JSONObject();
        JSONArray messages = new JSONArray();
        JSONObject msg = new JSONObject();
        msg.put("role", "user");

        JSONArray content = new JSONArray();

        JSONObject imageContent = new JSONObject();
        String imageValue = imageBase64;
        if (!imageBase64.startsWith("data:")) {
            imageValue = "data:image/jpeg;base64," + imageBase64;
        }
        imageContent.put("image", imageValue);
        content.add(imageContent);

        JSONObject textContent = new JSONObject();
        textContent.put("text", instruction);
        content.add(textContent);

        msg.put("content", content);
        messages.add(msg);
        input.put("messages", messages);
        jsonRequest.put("input", input);

        JSONObject parameters = new JSONObject();
        parameters.put("n", 1);
        parameters.put("size", config.getImage().getDefaultResolution());
        parameters.put("prompt_extend", true);
        jsonRequest.put("parameters", parameters);

        return executeImageApi(jsonRequest, "Image edit");
    }
}
