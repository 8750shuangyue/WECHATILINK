package com.example.demo.imagegen;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.ai.UserContextHolder;
import com.example.demo.config.DashScopeConfig;
import com.example.demo.core.UploadPathProvider;
import com.example.demo.gallery.MediaAssetService;
import com.example.demo.vision.ImageAnalysisResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class ImageGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationService.class);

    private final DashScopeConfig config;
    private final MediaAssetService mediaAssetService;
    private final UploadPathProvider uploadPathProvider;

    @Value("${app.http.image.connect-timeout-ms:5000}")
    private long connectTimeoutMs = 5000;

    @Value("${app.http.image.read-timeout-ms:120000}")
    private long readTimeoutMs = 120_000;

    @Value("${app.http.image.max-download-bytes:20971520}")
    private long maxDownloadBytes = 20L * 1024 * 1024;

    @Value("${app.guard.image.max-concurrent:2}")
    private int maxConcurrent = 2;

    @Value("${app.guard.acquire-timeout-ms:200}")
    private long acquireTimeoutMs = 200;

    private final OkHttpClient sharedHttpClient;
    private volatile OkHttpClient imageClient;
    private volatile Semaphore slots;

    public ImageGenerationService(DashScopeConfig config, MediaAssetService mediaAssetService,
                                  UploadPathProvider uploadPathProvider, OkHttpClient httpClient) {
        this.config = config;
        this.mediaAssetService = mediaAssetService;
        this.uploadPathProvider = uploadPathProvider;
        this.sharedHttpClient = httpClient;
    }

    private OkHttpClient client() {
        OkHttpClient current = imageClient;
        if (current == null) {
            synchronized (this) {
                if (imageClient == null) {
                    imageClient = sharedHttpClient.newBuilder()
                            .connectTimeout(Math.max(1, connectTimeoutMs), TimeUnit.MILLISECONDS)
                            .readTimeout(Math.max(1, readTimeoutMs), TimeUnit.MILLISECONDS)
                            .writeTimeout(Math.max(1, readTimeoutMs), TimeUnit.MILLISECONDS)
                            .build();
                }
                current = imageClient;
            }
        }
        return current;
    }

    private Semaphore slots() {
        Semaphore current = slots;
        if (current == null) {
            synchronized (this) {
                if (slots == null) {
                    slots = new Semaphore(Math.max(1, maxConcurrent));
                }
                current = slots;
            }
        }
        return current;
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
        logger.info("{} request: {}", operationName,
                com.example.demo.core.LogSanitizer.describe(jsonStr));

        Request request = new Request.Builder()
            .url(config.getImage().getBaseUrl())
            .header("Authorization", "Bearer " + imageApiKey())
            .post(RequestBody.create(jsonStr, MediaType.parse("application/json")))
            .build();

        return withGuard(() -> {
            try (Response response = client().newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                logger.info("{} response status: {}, body: {}",
                        operationName, response.code(),
                        com.example.demo.core.LogSanitizer.describe(responseBody));
                if (response.code() != 200) {
                    throw new IOException(operationName + " failed with status: " + response.code()
                            + ", bodyLen: " + responseBody.length());
                }
                return parseImageResponse(responseBody, operationName);
            }
        });
    }

    private String parseImageResponse(String responseBody, String operationName)
            throws IOException, InterruptedException {
        JSONObject responseJson = JSON.parseObject(responseBody);

        JSONObject output = responseJson.getJSONObject("output");
        if (output == null) {
            throw new RuntimeException("Invalid " + operationName + " response, bodyLen: "
                    + (responseBody == null ? 0 : responseBody.length()));
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
                        logger.info("Raw image value from API: {}",
                                com.example.demo.core.LogSanitizer.describe(imageValue));
                        return downloadAndSaveImage(imageValue);
                    }
                }
            }
        }

        throw new RuntimeException("Failed to get image URL from " + operationName
                    + " response, bodyLen: " + (responseBody == null ? 0 : responseBody.length()));
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
            imageBytes = withGuard(() -> {
                Request authRequest = new Request.Builder()
                        .url(url)
                        .header("Authorization", "Bearer " + imageApiKey())
                        .get()
                        .build();
                try (Response response = client().newCall(authRequest).execute()) {
                    if (response.code() == 200) {
                        return readResponseBytes(response);
                    }
                    logger.info("Auth download returned {}, retrying without auth", response.code());
                }
                // Retry without auth (public OSS signed URL)
                Request publicRequest = new Request.Builder().url(url).get().build();
                try (Response response = client().newCall(publicRequest).execute()) {
                    if (response.code() != 200) {
                        throw new IOException("Failed to download generated image, status: " + response.code());
                    }
                    return readResponseBytes(response);
                }
            });
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

        // Keep original PNG — JPEG conversion may produce incompatible format with WeChat

        String filename = "gen_" + UUID.randomUUID() + extension;
        Path targetPath = uploadPathProvider.resolve(filename);
        Files.write(targetPath, imageBytes);
        logger.info("Saved generated image to: {} ({} bytes)", targetPath, imageBytes.length);
        mediaAssetService.record(UserContextHolder.getUserId(), filename, "image", "generated");
        return targetPath.toString();
    }

    private byte[] readResponseBytes(Response response) throws IOException {
        if (response.body() == null) {
            return new byte[0];
        }
        try (InputStream input = response.body().byteStream()) {
            return readLimited(input, maxDownloadBytes);
        }
    }

    private <T> T withGuard(IOSupplier<T> action) throws IOException, InterruptedException {
        Semaphore permit = slots();
        if (!permit.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS)) {
            throw new IOException("图片服务繁忙，请稍后再试");
        }
        try {
            return action.get();
        } finally {
            permit.release();
        }
    }

    @FunctionalInterface
    private interface IOSupplier<T> {
        T get() throws IOException, InterruptedException;
    }

    private static byte[] readLimited(InputStream input, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("下载内容超过上限（" + limit + " 字节）");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
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
