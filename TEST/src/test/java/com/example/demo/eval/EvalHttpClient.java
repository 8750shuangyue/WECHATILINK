package com.example.demo.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class EvalHttpClient implements AutoCloseable {
    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String CURRENT_USER_PATH = "/api/auth/me";
    private static final String CHAT_STREAM_PATH = "/api/ai/chat/stream";
    private static final String CHAT_TOOLS_PATH = "/api/ai/chat-with-tools";
    private static final String RAG_RETRIEVALS_PATH = "/api/internal/rag/retrievals/";

    private final EvalConfig config;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EvalHttpClient(EvalConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void close() {
        httpClient.close();
    }

    public void login() throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("userName", config.username());
        body.put("password", config.password());

        int retryCount = 0;
        while (true) {
            try {
                HttpResponse<String> response = httpClient.send(
                        jsonRequest(LOGIN_PATH, body.toString()).build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (isRetryableStatus(response.statusCode()) && retryCount < config.maxRetries()) {
                    retryCount++;
                    continue;
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IOException("Login failed with HTTP " + response.statusCode());
                }
                JsonNode responseBody = objectMapper.readTree(response.body());
                if (responseBody.path("code").asInt() != 200) {
                    throw new IOException("Login was rejected by the application");
                }
                return;
            } catch (IOException exception) {
                if (retryCount < config.maxRetries()) {
                    retryCount++;
                    continue;
                }
                throw exception;
            }
        }
    }

    public void verifyLogin() throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(
                request(CURRENT_USER_PATH)
                        .header("Accept", "application/json")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Session verification failed with HTTP " + response.statusCode());
        }
        if (objectMapper.readTree(response.body()).path("code").asInt() != 200) {
            throw new IOException("Session verification returned an unsuccessful application response");
        }
    }

    public HttpResult chatStream(String message) throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("message", message);
        body.put("includeSources", "true");
        body.put("includeCitations", Boolean.toString(config.includeCitations()));

        HttpResult result = executeWithRetry(() -> streamOnce(message, body.toString()));
        if (result.status() == 401) {
            login();
            result = executeWithRetry(() -> streamOnce(message, body.toString()));
            result = result.withRetryCount(result.retryCount() + 1);
        }
        return result;
    }

    public HttpResult chatWithTools(String message, List<String> allowedTools)
            throws IOException, InterruptedException {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("message", message);
        ArrayNode toolArray = body.putArray("allowedTools");
        allowedTools.forEach(toolArray::add);

        HttpResult result = executeWithRetry(() -> toolsOnce(body.toString()));
        if (result.status() == 401) {
            login();
            result = executeWithRetry(() -> toolsOnce(body.toString()));
            result = result.withRetryCount(result.retryCount() + 1);
        }
        return result;
    }

    private HttpResult streamOnce(String ignoredMessage, String requestBody)
            throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        HttpResponse<Stream<String>> response = httpClient.send(
                jsonRequest(CHAT_STREAM_PATH, requestBody)
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofLines()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String responseBody = joinLimited(response.body(), 2_000);
            return new HttpResult(
                    response.statusCode(),
                    responseBody,
                    -1,
                    elapsedMillis(startedAt),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    "unavailable",
                    "Stream request failed with HTTP " + response.statusCode(),
                    0,
                    List.of(),
                    "unavailable"
            );
        }

        StreamState state = new StreamState();
        StringBuilder eventData = new StringBuilder();
        String currentEvent = "message";
        String streamError = null;

        try (Stream<String> lines = response.body()) {
            try {
                for (String line : (Iterable<String>) lines::iterator) {
                    if (line.isEmpty()) {
                        handleSseEvent(currentEvent, eventData.toString(), state, startedAt);
                        currentEvent = "message";
                        eventData.setLength(0);
                    } else if (line.startsWith("event:")) {
                        currentEvent = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        String payload = line.substring("data:".length());
                        if (payload.startsWith(" ")) {
                            payload = payload.substring(1);
                        }
                        if (eventData.length() > 0) {
                            eventData.append('\n');
                        }
                        eventData.append(payload);
                    }
                }
            } catch (UncheckedIOException exception) {
                // 生产 HTTP 链路可能在流中途重置；保留已收到的部分结果，
                // 让 runner 记为基础设施失败并继续后续用例，而不是整轮中止。
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                streamError = "Stream interrupted: " + cause.getClass().getSimpleName()
                        + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
            }
        }
        if (eventData.length() > 0) {
            handleSseEvent(currentEvent, eventData.toString(), state, startedAt);
        }

        RetrievalLookup retrieval = fetchRetrieval(state.traceId);

        return new HttpResult(
                response.statusCode(),
                state.responseText.toString(),
                state.firstTokenMs,
                elapsedMillis(startedAt),
                state.traceId,
                null,
                null,
                List.of(),
                retrieval.records(),
                retrieval.status(),
                streamError,
                0,
                state.sources,
                state.sourcesSeen ? "available" : "unavailable"
        );
    }

    /**
     * 命名事件分派：只有正文事件（message/无事件名）才拼入回答并记录首字延迟。
     * rag-sources 绝不能进入 response，否则会污染评分与延迟口径。
     */
    private void handleSseEvent(String eventName, String payload, StreamState state, long startedAt) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        if ("rag-trace".equals(eventName)) {
            state.traceId = parseTraceId(payload);
            return;
        }
        if ("rag-sources".equals(eventName)) {
            List<SourceRecord> parsed = parseSources(payload);
            if (parsed != null) {
                state.sources = parsed;
                state.sourcesSeen = true;
            }
            return;
        }
        if (!"message".equals(eventName)) {
            // 未知命名事件直接忽略，避免未来新增事件污染正文。
            return;
        }
        if ("[DONE]".equals(payload.trim())) {
            return;
        }
        String text = decodeSsePayload(payload);
        if (!text.isEmpty()) {
            if (state.firstTokenMs < 0) {
                state.firstTokenMs = elapsedMillis(startedAt);
            }
            state.responseText.append(text);
        }
    }

    private List<SourceRecord> parseSources(String payload) {
        try {
            JsonNode json = objectMapper.readTree(payload);
            JsonNode array = json.path("sources");
            if (!array.isArray()) {
                return null;
            }
            List<SourceRecord> sources = new ArrayList<>();
            for (JsonNode item : array) {
                sources.add(new SourceRecord(
                        textOrNull(item, "sourceId"),
                        textOrNull(item, "title"),
                        item.hasNonNull("rank") && item.path("rank").isNumber()
                                ? item.path("rank").intValue()
                                : null
                ));
            }
            return List.copyOf(sources);
        } catch (IOException exception) {
            return null;
        }
    }

    private static final class StreamState {
        private final StringBuilder responseText = new StringBuilder();
        private String traceId;
        private long firstTokenMs = -1;
        private boolean sourcesSeen;
        private List<SourceRecord> sources = List.of();
    }

    private HttpResult toolsOnce(String requestBody) throws IOException, InterruptedException {
        long startedAt = System.nanoTime();
        HttpResponse<String> response = httpClient.send(
                jsonRequest(CHAT_TOOLS_PATH, requestBody)
                        .header("Accept", "application/json")
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return new HttpResult(
                    response.statusCode(),
                    truncate(response.body(), 2_000),
                    -1,
                    elapsedMillis(startedAt),
                    null,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    "not_applicable",
                    "Tool request failed with HTTP " + response.statusCode(),
                    0,
                    List.of(),
                    "not_applicable"
            );
        }

        JsonNode json = objectMapper.readTree(response.body());
        String content = json.path("content").asText("");
        boolean success = json.path("success").asBoolean(false);
        String error = success ? null : json.path("error").asText("Tool request returned success=false");
        Long totalTokens = json.hasNonNull("totalTokens") && json.path("totalTokens").isNumber()
                ? json.path("totalTokens").longValue()
                : null;
        Integer totalIterations = json.hasNonNull("totalIterations") && json.path("totalIterations").isNumber()
                ? json.path("totalIterations").intValue()
                : null;

        return new HttpResult(
                response.statusCode(),
                content,
                content.isEmpty() ? -1 : elapsedMillis(startedAt),
                elapsedMillis(startedAt),
                textOrNull(json, "traceId"),
                totalIterations,
                totalTokens,
                parseToolCalls(json.path("toolCallHistory")),
                List.of(),
                "not_applicable",
                error,
                0,
                List.of(),
                "not_applicable"
        );
    }

    private String parseTraceId(String payload) {
        try {
            JsonNode json = objectMapper.readTree(payload);
            return textOrNull(json, "traceId");
        } catch (IOException ignored) {
            return null;
        }
    }

    private RetrievalLookup fetchRetrieval(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return RetrievalLookup.unavailable();
        }
        try {
            HttpResponse<String> response = httpClient.send(
                    request(RAG_RETRIEVALS_PATH + traceId)
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return RetrievalLookup.unavailable();
            }

            JsonNode body = objectMapper.readTree(response.body());
            if (body.path("code").asInt() != 200) {
                return RetrievalLookup.unavailable();
            }
            JsonNode results = body.path("data").path("results");
            if (!results.isArray()) {
                return RetrievalLookup.unavailable();
            }

            List<RetrievalRecord> records = new ArrayList<>();
            for (JsonNode item : results) {
                records.add(new RetrievalRecord(
                        textOrNull(item, "documentId"),
                        textOrNull(item, "sourceId"),
                        item.hasNonNull("similarity") && item.path("similarity").isNumber()
                                ? item.path("similarity").doubleValue()
                                : null,
                        textOrNull(item, "origin")
                ));
            }
            return new RetrievalLookup(List.copyOf(records), "available");
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return RetrievalLookup.unavailable();
        }
    }

    private List<ToolCallRecord> parseToolCalls(JsonNode history) {
        if (!history.isArray()) {
            return List.of();
        }
        List<ToolCallRecord> records = new ArrayList<>();
        for (JsonNode item : history) {
            records.add(new ToolCallRecord(
                    textOrNull(item, "toolName"),
                    item.path("success").asBoolean(false),
                    item.path("durationMs").asLong(0L),
                    jsonValue(item.get("timestamp")),
                    jsonValue(item.get("arguments")),
                    jsonValue(item.get("result")),
                    textOrNull(item, "errorMessage")
            ));
        }
        return List.copyOf(records);
    }

    private String decodeSsePayload(String payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        try {
            JsonNode json = objectMapper.readTree(payload);
            if (json.isTextual()) {
                return json.asText();
            }
            if (json.isObject()) {
                for (String field : List.of("content", "text", "message", "data")) {
                    JsonNode value = json.get(field);
                    if (value != null && value.isValueNode()) {
                        return value.asText();
                    }
                }
                return payload;
            }
        } catch (IOException ignored) {
            // Plain text SSE chunks are valid for the current SseEmitter implementation.
        }
        return payload;
    }

    private HttpResult executeWithRetry(CheckedSupplier<HttpResult> action)
            throws IOException, InterruptedException {
        int retryCount = 0;
        while (true) {
            try {
                HttpResult result = action.get();
                if (isRetryableStatus(result.status()) && retryCount < config.maxRetries()) {
                    retryCount++;
                    continue;
                }
                return result.withRetryCount(retryCount);
            } catch (IOException exception) {
                if (retryCount < config.maxRetries()) {
                    retryCount++;
                    continue;
                }
                throw exception;
            }
        }
    }

    private HttpRequest.Builder jsonRequest(String path, String body) {
        return request(path)
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                .timeout(config.requestTimeout())
                .header("Origin", config.origin())
                .header("Accept-Language", "zh-CN");
    }

    private boolean isRetryableStatus(int status) {
        return status == 408 || status >= 500;
    }

    private String joinLimited(Stream<String> lines, int maxLength) {
        StringBuilder result = new StringBuilder();
        try (lines) {
            lines.forEach(line -> {
                if (result.length() < maxLength) {
                    result.append(line).append('\n');
                }
            });
        }
        return truncate(result.toString(), maxLength);
    }

    private String jsonValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return node.toString();
    }

    private String textOrNull(JsonNode node, String fieldName) {
        if (node == null || !node.hasNonNull(fieldName)) {
            return null;
        }
        String value = node.path(fieldName).asText();
        return value == null || value.isBlank() ? null : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws IOException, InterruptedException;
    }

    public record ToolCallRecord(
            String toolName,
            boolean success,
            long durationMs,
            String timestamp,
            String arguments,
            String result,
            String errorMessage
    ) {
    }

    public record RetrievalRecord(
            String documentId,
            String sourceId,
            Double similarity,
            String origin
    ) {
    }

    private record RetrievalLookup(List<RetrievalRecord> records, String status) {
        private static RetrievalLookup unavailable() {
            return new RetrievalLookup(List.of(), "unavailable");
        }
    }

    public record SourceRecord(
            String sourceId,
            String title,
            Integer rank
    ) {
    }

    public record HttpResult(
            int status,
            String response,
            long firstTokenMs,
            long latencyMs,
            String traceId,
            Integer totalIterations,
            Long totalTokens,
            List<ToolCallRecord> toolCalls,
            List<RetrievalRecord> retrieval,
            String retrievalStatus,
            String error,
            int retryCount,
            List<SourceRecord> sources,
            String sourcesStatus
    ) {
        public HttpResult {
            response = response == null ? "" : response;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            retrieval = retrieval == null ? List.of() : List.copyOf(retrieval);
            retrievalStatus = retrievalStatus == null ? "unavailable" : retrievalStatus;
            sources = sources == null ? List.of() : List.copyOf(sources);
            sourcesStatus = sourcesStatus == null ? "unavailable" : sourcesStatus;
        }

        public HttpResult withRetryCount(int value) {
            return new HttpResult(
                    status,
                    response,
                    firstTokenMs,
                    latencyMs,
                    traceId,
                    totalIterations,
                    totalTokens,
                    toolCalls,
                    retrieval,
                    retrievalStatus,
                    error,
                    value,
                    sources,
                    sourcesStatus
            );
        }
    }
}
