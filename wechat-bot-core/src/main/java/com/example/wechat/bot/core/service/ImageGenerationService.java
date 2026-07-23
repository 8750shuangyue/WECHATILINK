package com.example.wechat.bot.core.service;

import com.example.wechat.bot.common.config.ChatApiProperties;
import com.example.wechat.bot.common.config.ImageGenProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class ImageGenerationService {

    private static final Logger logger = LoggerFactory.getLogger(ImageGenerationService.class);

    private final RestClient restClient;
    private final RestClient dashScopeClient;
    private final ChatApiProperties chatApiProperties;
    private final ImageGenProperties imageGenProperties;

    public ImageGenerationService(ChatApiProperties chatApiProperties, ImageGenProperties imageGenProperties) {
        this.chatApiProperties = chatApiProperties;
        this.imageGenProperties = imageGenProperties;
        this.restClient = RestClient.builder()
                .baseUrl(chatApiProperties.getBaseUrl())
                .build();
        this.dashScopeClient = RestClient.builder()
                .baseUrl("https://dashscope.aliyuncs.com")
                .build();
    }

    /**
     * 根据文字描述生成图片
     *
     * @param prompt 图片描述
     * @return 图片字节数组，失败返回 null
     */
    public byte[] generate(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            return null;
        }

        String apiKey = chatApiProperties.getKey();
        if (apiKey == null || apiKey.isBlank() || apiKey.contains("sk-demo-")) {
            logger.error("图片生成服务未配置");
            return null;
        }

        try {
            // 提交图片生成任务
            logger.info("提交图片生成任务: prompt={}", prompt);
            String taskId = submitTask(prompt, apiKey);
            if (taskId == null) {
                logger.error("submitTask 返回 null，无法获取任务 ID");
                return null;
            }

            logger.info("图片生成任务已提交, taskId={}", taskId);
            // 轮询等待任务完成
            byte[] imageData = pollTaskResult(taskId, apiKey);
            if (imageData == null) {
                logger.error("pollTaskResult 返回 null，图片生成或下载失败");
            } else {
                logger.info("图片生成成功，图片大小={} bytes", imageData.length);
            }
            return imageData;
        } catch (Exception e) {
            logger.error("图片生成失败", e);
            return null;
        }
    }

    private String submitTask(String prompt, String apiKey) {
        Map<String, Object> requestBody = Map.of(
                "model", imageGenProperties.getModel(),
                "input", Map.of("prompt", prompt),
                "parameters", Map.of(
                        "size", imageGenProperties.getSize(),
                        "style", imageGenProperties.getStyle(),
                        "n", 1
                )
        );

        try {
            SubmitResponse response = dashScopeClient.post()
                    .uri("/api/v1/services/aigc/text2image/image-synthesis")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .header("X-DashScope-Async", "enable")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                try {
                    byte[] body = res.getBody().readAllBytes();
                    String errorBody = new String(body, StandardCharsets.UTF_8);
                    logger.error("提交图片生成任务 API 返回错误: HTTP {} body={}", res.getStatusCode(), errorBody);
                    throw new RuntimeException("图片生成 API 返回错误: " + errorBody);
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                            throw new RuntimeException("读取 API 错误响应失败: " + e.getMessage(), e);
                        }
                    })
                    .body(SubmitResponse.class);

            if (response != null && response.output() != null) {
                return response.output().taskId();
            }
        } catch (Exception e) {
            logger.error("提交图片生成任务失败", e);
        }
        return null;
    }

    private byte[] pollTaskResult(String taskId, String apiKey) {
        int maxAttempts = imageGenProperties.getTimeoutSeconds() / 2;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                TimeUnit.SECONDS.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("轮询图片生成任务状态被中断");
                return null;
            }

            try {
                TaskStatusResponse status = dashScopeClient.get()
                        .uri("/api/v1/tasks/{taskId}", taskId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                        .retrieve()
                        .onStatus(HttpStatusCode::isError, (req, res) -> {
                            try {
                                byte[] body = res.getBody().readAllBytes();
                                String errorBody = new String(body, StandardCharsets.UTF_8);
                                logger.error("查询任务状态 API 返回错误: HTTP {} body={}", res.getStatusCode(), errorBody);
                                throw new RuntimeException("查询任务状态 API 返回错误: " + errorBody);
                            } catch (RuntimeException e) {
                                throw e;
                            } catch (Exception e) {
                                throw new RuntimeException("读取查询任务状态错误响应失败: " + e.getMessage(), e);
                            }
                        })
                        .body(TaskStatusResponse.class);

                if (status == null || status.output() == null) {
                    logger.debug("任务状态 response 异常: status={}", status);
                    continue;
                }

                String taskStatus = status.output().taskStatus();
                logger.debug("图片生成任务状态: {}", taskStatus);

                if ("SUCCEEDED".equals(taskStatus)) {
                    return downloadImage(status.output().results());
                } else if ("FAILED".equals(taskStatus)) {
                    logger.error("图片生成任务失败, taskId={}, message={}", taskId, status.output().message());
                    return null;
                }
                logger.debug("图片生成任务仍在进行: taskId={}, status={}, attempts={}/{}", taskId, taskStatus, attempt, maxAttempts);
            } catch (Exception e) {
                logger.error("查询图片生成任务状态失败(第{}/{}次)", attempt, maxAttempts, e);
            }
        }

        logger.error("图片生成超时 ({} 次尝试后)", maxAttempts);
        return null;
    }

    private byte[] downloadImage(List<TaskResult> results) {
        if (results == null || results.isEmpty()) {
            logger.error("下载图片失败: results 为空");
            return null;
        }

        String imageUrl = results.get(0).url();
        if (imageUrl == null || imageUrl.isBlank()) {
            logger.error("下载图片失败: imageUrl 为空");
            return null;
        }

        logger.info("开始下载图片: url={}", imageUrl);
        try {
            return restClient.get()
                    .uri(URI.create(imageUrl))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        try {
                            byte[] body = res.getBody().readAllBytes();
                            String errorBody = new String(body, StandardCharsets.UTF_8);
                            logger.error("下载图片 API 返回错误: HTTP {} body={}", res.getStatusCode(), errorBody);
                            throw new RuntimeException("下载图片 API 返回错误: " + errorBody);
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new RuntimeException("读取下载图片错误响应失败: " + e.getMessage(), e);
                        }
                    })
                    .body(byte[].class);
        } catch (Exception e) {
            logger.error("下载生成的图片失败", e);
            return null;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SubmitResponse(Output output) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Output(
                @JsonProperty("task_id") String taskId,
                @JsonProperty("task_status") String taskStatus
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskStatusResponse(Output output) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Output(
                @JsonProperty("task_id") String taskId,
                @JsonProperty("task_status") String taskStatus,
                String message,
                List<TaskResult> results
        ) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskResult(String url) {}
}
