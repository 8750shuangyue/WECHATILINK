package com.example.demo.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
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
                    "Stream request failed with HTTP " + response.statusCode(),
                    0
            );
        }

        StringBuilder responseText = new StringBuilder();
        StringBuilder eventData = new StringBuilder();
        long firstTokenMs = -1;

        try (Stream<String> lines = response.body()) {
            for (String line : (Iterable<String>) lines::iterator) {
                if (line.startsWith("data:")) {
                    String payload = line.substring("data:".length());
                    if (payload.startsWith(" ")) {
                        payload = payload.substring(1);
                    }
                    if ("[DONE]".equals(payload.trim())) {
                        break;
                    }
                    String text = decodeSsePayload(payload);
                    if (!text.isEmpty()) {
                        if (firstTokenMs < 0) {
                            firstTokenMs = elapsedMillis(startedAt);
                        }
                        eventData.append(text);
                    }
                } else if (line.isEmpty() && eventData.length() > 0) {
                    responseText.append(eventData);
                    eventData.setLength(0);
                }
            }
        }
        if (eventData.length() > 0) {
            responseText.append(eventData);
        }

        return new HttpResult(
                response.statusCode(),
                responseText.toString(),
                firstTokenMs,
                elapsedMillis(startedAt),
                null,
                null,
                null,
                List.of(),
                null,
                0
        );
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
                    "Tool request failed with HTTP " + response.statusCode(),
                    0
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
                error,
                0
        );
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

    public record HttpResult(
            int status,
            String response,
            long firstTokenMs,
            long latencyMs,
            String traceId,
            Integer totalIterations,
            Long totalTokens,
            List<ToolCallRecord> toolCalls,
            String error,
            int retryCount
    ) {
        public HttpResult {
            response = response == null ? "" : response;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
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
                    error,
                    value
            );
        }
    }
}
