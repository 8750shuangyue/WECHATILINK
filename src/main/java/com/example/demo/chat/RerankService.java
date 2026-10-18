package com.example.demo.chat;

import com.alibaba.dashscope.protocol.ConnectionOptions;
import com.alibaba.dashscope.rerank.TextReRank;
import com.alibaba.dashscope.rerank.TextReRankOutput;
import com.alibaba.dashscope.rerank.TextReRankParam;
import com.alibaba.dashscope.rerank.TextReRankResult;
import com.example.demo.config.DashScopeConfig;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * DashScope gte-rerank 封装。任何失败都返回 applied=false，由调用方回退到余弦顺序；
 * 这里绝不向回答链路抛异常，也不记录 query/正文。
 */
@Service
public class RerankService {

    private static final Logger logger = LoggerFactory.getLogger(RerankService.class);

    public static final String FALLBACK_MISSING_API_KEY = "missing_api_key";
    public static final String FALLBACK_TIMEOUT = "timeout";
    public static final String FALLBACK_API_ERROR = "api_error";
    public static final String FALLBACK_INVALID_RESPONSE = "invalid_response";
    public static final String FALLBACK_EXECUTOR_REJECTED = "executor_rejected";
    public static final String FALLBACK_INTERRUPTED = "interrupted";
    public static final String FALLBACK_NO_DOCUMENTS = "no_documents";

    private final DashScopeConfig config;
    private final int timeoutMs;
    private final int maxDocChars;
    private final ThreadPoolExecutor executor;
    private final RerankClient client;

    @Autowired
    public RerankService(DashScopeConfig config,
                         @Value("${chat.vectorstore.rerank.timeout-ms:800}") int timeoutMs,
                         @Value("${chat.vectorstore.rerank.max-doc-chars:2000}") int maxDocChars,
                         @Value("${chat.vectorstore.rerank.max-concurrent:2}") int maxConcurrent,
                         @Value("${chat.vectorstore.rerank.queue-size:8}") int queueSize) {
        this(config, timeoutMs, maxDocChars, maxConcurrent, queueSize, null);
    }

    RerankService(DashScopeConfig config, int timeoutMs, int maxDocChars,
                  int maxConcurrent, int queueSize, RerankClient client) {
        this.config = config;
        this.timeoutMs = Math.max(1, timeoutMs);
        this.maxDocChars = Math.max(1, maxDocChars);
        this.executor = new ThreadPoolExecutor(
                Math.max(1, maxConcurrent),
                Math.max(1, maxConcurrent),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueSize)),
                runnable -> {
                    Thread thread = new Thread(runnable, "rag-rerank");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        this.client = client != null ? client : this::callDashScope;
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public RerankOutcome rerank(String query, List<String> documents) {
        long startedAt = System.nanoTime();
        String model = getModel();
        if (documents == null || documents.isEmpty()) {
            return RerankOutcome.fallback(FALLBACK_NO_DOCUMENTS, model, elapsed(startedAt));
        }
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return RerankOutcome.fallback(FALLBACK_MISSING_API_KEY, model, elapsed(startedAt));
        }

        List<String> prepared = new ArrayList<>(documents.size());
        for (String document : documents) {
            prepared.add(truncate(document));
        }

        Future<List<Double>> future = null;
        try {
            future = executor.submit(() -> client.rerank(query, prepared));
            List<Double> scores = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            List<Double> validated = validate(scores, prepared.size());
            if (validated == null) {
                return RerankOutcome.fallback(
                        FALLBACK_INVALID_RESPONSE, model, elapsed(startedAt));
            }
            return new RerankOutcome(validated, true, elapsed(startedAt), null, model);
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            return RerankOutcome.fallback(FALLBACK_TIMEOUT, model, elapsed(startedAt));
        } catch (RejectedExecutionException e) {
            return RerankOutcome.fallback(
                    FALLBACK_EXECUTOR_REJECTED, model, elapsed(startedAt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RerankOutcome.fallback(FALLBACK_INTERRUPTED, model, elapsed(startedAt));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            logger.warn("[RAG] Rerank call failed: {}: {}",
                    cause.getClass().getSimpleName(), cause.getMessage());
            return RerankOutcome.fallback(FALLBACK_API_ERROR, model, elapsed(startedAt));
        }
    }

    public String getModel() {
        return config.getRerank() == null ? null : config.getRerank().getModel();
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    private String resolveApiKey() {
        if (config.getRerank() != null && config.getRerank().getApiKey() != null
                && !config.getRerank().getApiKey().isBlank()) {
            return config.getRerank().getApiKey();
        }
        return config.getEmbedding() == null ? null : config.getEmbedding().getApiKey();
    }

    private List<Double> callDashScope(String query, List<String> documents) throws Exception {
        String apiKey = resolveApiKey();
        ConnectionOptions connectionOptions = ConnectionOptions.builder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .readTimeout(Duration.ofMillis(timeoutMs))
                .writeTimeout(Duration.ofMillis(timeoutMs))
                .build();
        TextReRank reranker = new TextReRank(
                "http", config.getRerank().getBaseUrl(), connectionOptions);
        TextReRankParam param = TextReRankParam.builder()
                .apiKey(apiKey)
                .model(getModel())
                .query(query)
                .documents(documents)
                .topN(documents.size())
                .returnDocuments(false)
                .build();
        TextReRankResult result = reranker.call(param);
        if (result == null || result.getOutput() == null
                || result.getOutput().getResults() == null) {
            throw new IllegalStateException("empty rerank response");
        }

        Double[] scores = new Double[documents.size()];
        for (TextReRankOutput.Result item : result.getOutput().getResults()) {
            if (item == null || item.getIndex() == null || item.getRelevanceScore() == null) {
                throw new IllegalStateException("incomplete rerank result item");
            }
            int index = item.getIndex();
            if (index < 0 || index >= scores.length || scores[index] != null) {
                throw new IllegalStateException("invalid rerank index: " + index);
            }
            scores[index] = item.getRelevanceScore();
        }
        return Arrays.asList(scores);
    }

    private List<Double> validate(List<Double> scores, int expected) {
        if (scores == null || scores.size() != expected) {
            return null;
        }
        for (Double score : scores) {
            if (score == null || score.isNaN() || score.isInfinite()) {
                return null;
            }
        }
        return List.copyOf(scores);
    }

    private String truncate(String document) {
        if (document == null) {
            return "";
        }
        return document.length() <= maxDocChars
                ? document
                : document.substring(0, maxDocChars);
    }

    private long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    @FunctionalInterface
    interface RerankClient {
        List<Double> rerank(String query, List<String> documents) throws Exception;
    }

    public record RerankOutcome(List<Double> scoresByIndex, boolean applied,
                                long durationMs, String fallbackReason, String model) {
        public static RerankOutcome fallback(String reason, String model, long durationMs) {
            return new RerankOutcome(List.of(), false, durationMs, reason, model);
        }
    }
}
