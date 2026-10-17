package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.DashScopeConfig;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * DashScope / OpenAI-compatible Embedding 客户端。
 *
 * <p>批量接口一次请求提交多个 input；返回按 response.data[].index 还原顺序。
 * 整批失败会二分拆批，最终仍失败则抛出，不静默丢弃输入。</p>
 */
@Service
public class EmbeddingService {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddingService.class);
    private static final int MODEL_MAX_BATCH_SIZE = 25;

    private final DashScopeConfig config;
    private final CloseableHttpClient httpClient;

    public EmbeddingService(DashScopeConfig config) {
        this.config = config;
        int timeoutSeconds = Math.max(1, config.getEmbedding().getTimeoutSeconds());
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(timeoutSeconds))
                .setConnectionRequestTimeout(Timeout.ofSeconds(timeoutSeconds))
                .setResponseTimeout(Timeout.ofSeconds(timeoutSeconds))
                .build();
        this.httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .build();
    }

    @PreDestroy
    public void close() {
        try {
            httpClient.close();
        } catch (IOException exception) {
            logger.warn("Failed to close embedding HTTP client: {}", exception.getMessage());
        }
    }

    public float[] embed(String text) throws IOException {
        if (text == null || text.isEmpty()) {
            return new float[0];
        }
        List<float[]> embeddings = requestWithRetry(List.of(text), false);
        return embeddings.isEmpty() ? new float[0] : embeddings.get(0);
    }

    public List<float[]> embedBatch(List<String> texts) throws IOException {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        if (!config.getEmbedding().isBatchEnabled()) {
            List<float[]> embeddings = new ArrayList<>(texts.size());
            for (String text : texts) {
                embeddings.add(embed(text));
            }
            return embeddings;
        }

        List<float[]> results = new ArrayList<>(texts.size());
        List<Integer> nonEmptyIndexes = new ArrayList<>();
        List<String> nonEmptyTexts = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            results.add(new float[0]);
            if (text != null && !text.isEmpty()) {
                nonEmptyIndexes.add(i);
                nonEmptyTexts.add(text);
            }
        }

        int batchSize = Math.max(1,
                Math.min(MODEL_MAX_BATCH_SIZE, config.getEmbedding().getBatchSize()));
        logger.info("[Embedding] batch embedding: inputs={}, batchSize={}, batches={}",
                nonEmptyTexts.size(), batchSize,
                (nonEmptyTexts.size() + batchSize - 1) / batchSize);
        for (int start = 0; start < nonEmptyTexts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, nonEmptyTexts.size());
            List<String> batch = nonEmptyTexts.subList(start, end);
            List<float[]> batchEmbeddings = requestBatchWithFallback(batch);
            if (batchEmbeddings.size() != batch.size()) {
                throw new IOException("Embedding batch returned " + batchEmbeddings.size()
                        + " items for " + batch.size() + " inputs");
            }
            for (int i = 0; i < batchEmbeddings.size(); i++) {
                results.set(nonEmptyIndexes.get(start + i), batchEmbeddings.get(i));
            }
        }
        return results;
    }

    private List<float[]> requestBatchWithFallback(List<String> inputs) throws IOException {
        if (inputs.size() <= 1) {
            return requestWithRetry(inputs, true);
        }
        try {
            return requestWithRetry(inputs, true);
        } catch (IOException exception) {
            int middle = inputs.size() / 2;
            logger.warn("Embedding batch of {} failed ({}); splitting into {} + {}",
                    inputs.size(), exception.getMessage(), middle, inputs.size() - middle);
            List<float[]> merged = new ArrayList<>(inputs.size());
            merged.addAll(requestBatchWithFallback(inputs.subList(0, middle)));
            merged.addAll(requestBatchWithFallback(inputs.subList(middle, inputs.size())));
            return merged;
        }
    }

    private List<float[]> requestWithRetry(List<String> inputs, boolean strict) throws IOException {
        int maxAttempts = Math.max(1, config.getEmbedding().getMaxAttempts());
        long backoffMs = Math.max(0, config.getEmbedding().getRetryBackoffMs());
        IOException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return requestEmbeddings(inputs, strict);
            } catch (EmbeddingRequestException exception) {
                lastFailure = exception;
                if (!exception.isRetryable() || attempt >= maxAttempts) {
                    throw exception;
                }
                logger.warn("Embedding request failed with status {}, attempt {}/{}",
                        exception.getStatus(), attempt, maxAttempts);
            } catch (IOException exception) {
                lastFailure = exception;
                if (attempt >= maxAttempts) {
                    throw exception;
                }
                logger.warn("Embedding request failed ({}), attempt {}/{}",
                        exception.getMessage(), attempt, maxAttempts);
            }
            sleep(backoffMs * (1L << Math.min(attempt - 1, 5)));
        }
        throw lastFailure == null ? new IOException("Embedding request failed") : lastFailure;
    }

    private List<float[]> requestEmbeddings(List<String> inputs, boolean strict) throws IOException {
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", config.getEmbedding().getModel());
        JSONArray input = new JSONArray();
        for (String text : inputs) {
            input.add(text);
        }
        requestBody.put("input", input);

        HttpPost httpPost = new HttpPost(config.getEmbedding().getBaseUrl() + "/embeddings");
        httpPost.setHeader("Content-Type", "application/json");
        httpPost.setHeader("Authorization", "Bearer " + config.getEmbedding().getApiKey());
        httpPost.setEntity(new StringEntity(
                JSON.toJSONString(requestBody), ContentType.APPLICATION_JSON));

        long startedAt = System.nanoTime();
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            HttpEntity entity = response.getEntity();
            String responseBody = entity == null ? "" : EntityUtils.toString(entity, "UTF-8");
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            logger.info("[Embedding] batch request size={}, status={}, elapsedMs={}",
                    inputs.size(), response.getCode(), elapsedMs);
            if (response.getCode() != 200) {
                throw new EmbeddingRequestException(response.getCode(), responseBody);
            }
            return parseEmbeddings(responseBody, inputs.size(), strict);
        } catch (ParseException exception) {
            throw new IOException("Failed to parse embedding response", exception);
        }
    }

    private List<float[]> parseEmbeddings(String responseBody, int expectedCount, boolean strict)
            throws IOException {
        JSONObject response = JSON.parseObject(responseBody);
        JSONArray data = response == null ? null : response.getJSONArray("data");
        if (data == null) {
            if (strict) {
                throw new IOException("Embedding response does not contain data");
            }
            return List.of(new float[0]);
        }

        List<float[]> embeddings = new ArrayList<>(expectedCount);
        for (int i = 0; i < expectedCount; i++) {
            embeddings.add(new float[0]);
        }

        int dimension = -1;
        for (int i = 0; i < data.size(); i++) {
            JSONObject item = data.getJSONObject(i);
            if (item == null) {
                if (strict) {
                    throw new IOException("Embedding response item " + i + " is null");
                }
                continue;
            }
            int index = item.getIntValue("index", i);
            if (index < 0 || index >= expectedCount) {
                if (strict) {
                    throw new IOException("Embedding response contains out-of-range index " + index);
                }
                continue;
            }
            JSONArray vectorArray = item.getJSONArray("embedding");
            if (vectorArray == null) {
                if (strict) {
                    throw new IOException("Embedding response item " + index + " has no embedding");
                }
                continue;
            }
            float[] vector = new float[vectorArray.size()];
            for (int j = 0; j < vectorArray.size(); j++) {
                vector[j] = vectorArray.getFloatValue(j);
            }
            if (strict) {
                if (dimension < 0) {
                    dimension = vector.length;
                } else if (dimension != vector.length) {
                    throw new IOException("Embedding dimensions are inconsistent");
                }
                if (embeddings.get(index).length != 0) {
                    throw new IOException("Embedding response contains duplicate index " + index);
                }
            }
            embeddings.set(index, vector);
        }

        if (strict) {
            if (data.size() != expectedCount) {
                throw new IOException("Embedding response returned " + data.size()
                        + " items for " + expectedCount + " inputs");
            }
            for (float[] embedding : embeddings) {
                if (embedding.length == 0) {
                    throw new IOException("Embedding response is missing an input");
                }
            }
        }
        return embeddings;
    }

    private void sleep(long millis) throws IOException {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while retrying embedding request", exception);
        }
    }

    private static final class EmbeddingRequestException extends IOException {
        private final int status;

        private EmbeddingRequestException(int status, String responseBody) {
            super("Embedding API request failed with status " + status
                    + (responseBody == null || responseBody.isBlank()
                    ? "" : ", response: " + truncate(responseBody, 500)));
            this.status = status;
        }

        private int getStatus() {
            return status;
        }

        private boolean isRetryable() {
            return status == 429 || status >= 500;
        }

        private static String truncate(String value, int maxLength) {
            return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
        }
    }
}
