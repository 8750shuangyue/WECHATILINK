package com.example.wechat.bot.core.service;

import com.example.wechat.bot.common.config.ChatApiProperties;
import com.example.wechat.bot.common.config.VisionProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import com.example.wechat.bot.common.util.StringUtil;

@Service
public class VisionService {

    private static final Logger logger = LoggerFactory.getLogger(VisionService.class);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private static final String INTENT_SYSTEM_PROMPT =
        "你是一个智能图片分析助手。请分析用户发送的图片和文字，判断用户意图。\n"
        + "你必须按 JSON 格式回复，格式如下：\n"
        + "如果用户意图是描述图片、识别内容、回答关于图片的问题：\n"
        + "{\"intent\": \"describe\", \"reply\": \"对图片的描述或问题回答\"}\n"
        + "如果用户意图是基于参考图生成新图片、创作类似风格图片、对图片内容进行再创作等：\n"
        + "{\"intent\": \"generate\", \"prompt\": \"结合用户要求和参考图特征的详细图片描述，可直接作为图片生成模型的 prompt\"}\n"
        + "否则 intent 为 describe。\n"
        + "请严格按 JSON 格式输出，不要包含其他说明文字。";

    private final RestClient restClient;
    private final ChatApiProperties chatApiProperties;
    private final VisionProperties visionProperties;

    public VisionService(ChatApiProperties chatApiProperties, VisionProperties visionProperties) {
        this.chatApiProperties = chatApiProperties;
        this.visionProperties = visionProperties;
        this.restClient = RestClient.builder()
                .baseUrl(chatApiProperties.getBaseUrl())
                .build();
    }

    /**
     * 分析图片内容
     *
     * @param imageBytes 图片字节数组
     * @return 识别结果文本
     */
    public String analyze(byte[] imageBytes) {
        return analyze(imageBytes, "请描述这张图片的内容");
    }

    /**
     * 分析图片内容并回答问题
     *
     * @param imageBytes 图片字节数组
     * @param question   关于图片的问题
     * @return 识别结果文本
     */
    public String analyze(byte[] imageBytes, String question) {
        if (imageBytes == null || imageBytes.length == 0) {
            return "无法识别：图片数据为空";
        }

        String apiKey = chatApiProperties.getKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("sk-demo-")) {
            return "视觉识别服务未配置，请先配置 chat.api.key";
        }

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> requestBody = Map.of(
                "model", visionProperties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", "你是一个图片识别助手，能够准确描述图片内容并回答相关问题。"),
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + base64Image)),
                                Map.of("type", "text", "text", question)
                        ))
                ),
                "max_tokens", visionProperties.getMaxTokens(),
                "temperature", visionProperties.getTemperature()
        );

        try {
            VisionResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        try {
                            byte[] body = res.getBody().readAllBytes();
                            String errorBody = new String(body, StandardCharsets.UTF_8);
                            throw new RuntimeException("视觉 API 返回错误 (HTTP " + res.getStatusCode() + "): " + errorBody);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException("读取 API 错误响应失败: " + e.getMessage(), e);
                        }
                    })
                    .body(VisionResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new IllegalStateException("视觉 API 未返回有效内容");
            }

            VisionResponse.Choice choice = response.choices().get(0);
            if (choice.message() == null || choice.message().content() == null) {
                throw new IllegalStateException("视觉 API 返回消息为空");
            }

            return choice.message().content().trim();
        } catch (Exception e) {
            logger.error("调用视觉 API 失败", e);
            return "图片识别失败：" + e.getMessage();
        }
    }

    /**
     * 分析图片并判断用户意图（describe / generate），返回结构化结果
     *
     * @param imageBytes 图片字节数组
     * @param question   用户针对图片的问题或指令
     * @return 意图分析结果
     */
    public VisionIntentResult analyzeIntent(byte[] imageBytes, String question) {
        if (imageBytes == null || imageBytes.length == 0) {
            return new VisionIntentResult("describe", "无法识别：图片数据为空", null);
        }

        String apiKey = chatApiProperties.getKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("sk-demo-")) {
            return new VisionIntentResult("describe", "视觉识别服务未配置，请先配置 chat.api.key", null);
        }

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> requestBody = Map.of(
                "model", visionProperties.getModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", INTENT_SYSTEM_PROMPT),
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + base64Image)),
                                Map.of("type", "text", "text", question)
                        ))
                ),
                "max_tokens", visionProperties.getMaxTokens(),
                "temperature", visionProperties.getTemperature()
        );

        try {
            VisionResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        try {
                            byte[] body = res.getBody().readAllBytes();
                            String errorBody = new String(body, StandardCharsets.UTF_8);
                            throw new RuntimeException("视觉 API 返回错误 (HTTP " + res.getStatusCode() + "): " + errorBody);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException("读取 API 错误响应失败: " + e.getMessage(), e);
                        }
                    })
                    .body(VisionResponse.class);

            if (response == null || response.choices() == null || response.choices().isEmpty()) {
                throw new IllegalStateException("视觉 API 未返回有效内容");
            }

            VisionResponse.Choice choice = response.choices().get(0);
            if (choice.message() == null || choice.message().content() == null) {
                throw new IllegalStateException("视觉 API 返回消息为空");
            }

            String content = choice.message().content().trim();
            // 解析 JSON 意图
            try {
                JsonNode root = JSON_MAPPER.readTree(StringUtil.extractJsonFromResponse(content));
                String intent = root.has("intent") ? root.get("intent").asText() : "describe";
                if ("generate".equals(intent)) {
                    String prompt = root.has("prompt") ? root.get("prompt").asText() : question;
                    return new VisionIntentResult("generate", null, prompt);
                }
                String reply = root.has("reply") ? root.get("reply").asText() : content;
                return new VisionIntentResult("describe", reply, null);
            } catch (JsonProcessingException e) {
                logger.warn("意图分析 JSON 解析失败，降级为 describe: {}", content, e);
                return new VisionIntentResult("describe", content, null);
            }
        } catch (Exception e) {
            logger.error("调用视觉 API 失败", e);
            return new VisionIntentResult("describe", "图片识别失败：" + e.getMessage(), null);
        }
    }

    public record VisionResponse(List<Choice> choices) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Choice(VisionMessage message) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record VisionMessage(String role, String content) {}
    }

    /**
     * 多模态意图分析结果
     *
     * @param intent "describe" 或 "generate"
     * @param reply  描述/问答结果（intent=describe 时使用）
     * @param prompt 优化后的图片描述（intent=generate 时使用）
     */
    public record VisionIntentResult(String intent, String reply, String prompt) {}
}
