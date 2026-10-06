package com.example.demo.vision;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.DashScopeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

@Service
public class VisionService {

    private static final Logger logger = LoggerFactory.getLogger(VisionService.class);

    private final DashScopeConfig config;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public VisionService(DashScopeConfig config) {
        this.config = config;
    }

    private String visionBaseUrl() {
        String url = config.getVision().getBaseUrl();
        return (url != null && !url.isBlank()) ? url : config.getBaseUrl();
    }

    private String visionApiKey() {
        String key = config.getVision().getApiKey();
        return (key != null && !key.isBlank()) ? key : config.getApiKey();
    }

    private static final String PROMPT = "请分析图片内容，并以纯JSON格式返回以下信息，不要包含markdown标记：" +
            "{\"title\": \"图片标题\", \"description\": \"详细描述\", \"objects\": [\"物体1\",\"物体2\"], " +
            "\"scene\": \"场景描述\", \"emotion\": \"情感色彩\", \"tags\": [\"标签1\",\"标签2\"], \"text\": \"图片中的文字\"}";

    private static final String MULTIMODAL_SYSTEM_PROMPT = "你是一个智能助手。当用户发送图片后，紧接着发送文本指令时，请将这两者视为同一个任务。请结合图片内容进行理解，并直接执行文本中的提取或修改要求。如果用户只发了图片没有发指令，请礼貌地询问用户需要对图片做什么操作。";

    public ImageAnalysisResponse analyzeImage(byte[] imageBytes) throws IOException {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        return analyzeImageWithUrl("data:image/jpeg;base64," + base64Image);
    }

    public ImageAnalysisResponse analyzeImageWithUrl(String imageUrl) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getVision().getModel());
        
        JSONArray messages = new JSONArray();
        JSONObject message = new JSONObject();
        message.put("role", "user");
        
        JSONArray content = new JSONArray();
        
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", PROMPT);
        content.add(textContent);
        
        JSONObject imageContent = new JSONObject();
        imageContent.put("type", "image_url");
        JSONObject imageUrlObj = new JSONObject();
        imageUrlObj.put("url", imageUrl);
        imageContent.put("image_url", imageUrlObj);
        content.add(imageContent);
        
        message.put("content", content);
        messages.add(message);
        requestBody.put("messages", messages);
        
        requestBody.put("stream", false);
        
        String jsonRequest = JSON.toJSONString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(visionBaseUrl() + "/chat/completions"))
            .header("Authorization", "Bearer " + visionApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
            .build();
        
        String responseBody;
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            responseBody = response.body();
            
            logger.info("Vision API response status: {}, body length: {}", 
                    response.statusCode(), responseBody != null ? responseBody.length() : 0);
            
            if (response.statusCode() != 200) {
                throw new IOException("Vision API request failed with status: " + response.statusCode() + ", body: " + responseBody);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Vision API request was interrupted", e);
        }
        
        return parseVisionResponse(responseBody);
    }

    public String analyzeImageWithCustomPrompt(byte[] imageBytes, String prompt) throws IOException {
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        return analyzeImageWithCustomPrompt("data:image/jpeg;base64," + base64Image, prompt);
    }

    public String analyzeImageWithCustomPrompt(String imageUrl, String prompt) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getVision().getModel());
        
        JSONArray messages = new JSONArray();
        
        JSONObject systemMessage = new JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", MULTIMODAL_SYSTEM_PROMPT);
        messages.add(systemMessage);
        
        JSONObject userMessage = new JSONObject();
        userMessage.put("role", "user");
        
        JSONArray content = new JSONArray();
        
        JSONObject imageContent = new JSONObject();
        imageContent.put("type", "image_url");
        JSONObject imageUrlObj = new JSONObject();
        imageUrlObj.put("url", imageUrl);
        imageContent.put("image_url", imageUrlObj);
        content.add(imageContent);
        
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", prompt);
        content.add(textContent);
        
        userMessage.put("content", content);
        messages.add(userMessage);
        requestBody.put("messages", messages);
        
        requestBody.put("stream", false);
        
        String jsonRequest = JSON.toJSONString(requestBody);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(visionBaseUrl() + "/chat/completions"))
            .header("Authorization", "Bearer " + visionApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonRequest))
            .build();
        
        String responseBody;
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            responseBody = response.body();
            
            logger.info("Vision API response status: {}, body length: {}", 
                    response.statusCode(), responseBody != null ? responseBody.length() : 0);
            
            if (response.statusCode() != 200) {
                throw new IOException("Vision API request failed with status: " + response.statusCode() + ", body: " + responseBody);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Vision API request was interrupted", e);
        }
        
        return parseVisionResponseToText(responseBody);
    }

    private String parseVisionResponseToText(String responseBody) {
        JSONObject responseJson = JSON.parseObject(responseBody);
        
        if (responseJson == null || !responseJson.containsKey("choices")) {
            throw new RuntimeException("Invalid vision API response: " + responseBody);
        }
        
        JSONArray choices = responseJson.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("No choices in vision API response");
        }
        
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.getJSONObject("message");
        String content = message.getString("content");
        
        content = content.trim();
        if (content.startsWith("```json")) {
            content = content.substring(7);
        }
        if (content.startsWith("```")) {
            content = content.substring(3);
        }
        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3);
        }
        content = content.trim();
        
        return content;
    }

    private ImageAnalysisResponse parseVisionResponse(String responseBody) {
        JSONObject responseJson = JSON.parseObject(responseBody);
        
        if (responseJson == null || !responseJson.containsKey("choices")) {
            throw new RuntimeException("Invalid vision API response: " + responseBody);
        }
        
        JSONArray choices = responseJson.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("No choices in vision API response");
        }
        
        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.getJSONObject("message");
        String content = message.getString("content");
        
        content = content.trim();
        if (content.startsWith("```json")) {
            content = content.substring(7);
        }
        if (content.startsWith("```")) {
            content = content.substring(3);
        }
        if (content.endsWith("```")) {
            content = content.substring(0, content.length() - 3);
        }
        content = content.trim();
        
        return JSON.parseObject(content, ImageAnalysisResponse.class);
    }
}
