package com.example.demo.eval;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class EvalHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(180);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String baseUrl;
    private final String origin;
    private final String username;
    private final String password;

    public EvalHttpClient(ObjectMapper objectMapper, String baseUrl, String origin,
                          String username, String password) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.origin = origin;
        this.username = username;
        this.password = password;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .build();
    }

    public boolean login() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return tryLogin();
            } catch (Exception ex) {
                if (attempt == 1) {
                    throw ex;
                }
            }
        }
        return false;
    }

    private boolean tryLogin() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "userName", username,
                "password", password
        ));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Origin", origin)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            return false;
        }
        JsonNode root = objectMapper.readTree(response.body());
        JsonNode code = root.get("code");
        return code != null && code.asInt() == 200;
    }

    public ChatResponse chatStream(String question) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("message", question));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/ai/chat/stream"))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .header("Origin", origin)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long start = System.nanoTime();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        long firstToken = -1;
        StringBuilder text = new StringBuilder();
        long tokens = 0;
        String lastData = null;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (firstToken < 0 && !line.isBlank() && !line.startsWith(":")) {
                    firstToken = System.nanoTime();
                }
                if (line.startsWith("data:")) {
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    lastData = payload;
                    appendPayload(text, payload);
                }
            }
        }
        long end = System.nanoTime();
        long latencyMs = (end - start) / 1_000_000;
        long firstTokenMs = firstToken < 0 ? latencyMs : (firstToken - start) / 1_000_000;
        if (lastData != null) {
            tokens = extractTokens(lastData);
        }

        return new ChatResponse(response.statusCode(), text.toString(), firstTokenMs, latencyMs, tokens);
    }

    public ToolsResponse chatWithTools(String question) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("message", question));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/ai/chat-with-tools"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Origin", origin)
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        long start = System.nanoTime();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        String raw = response.body() == null ? "" : response.body();

        ToolsResponse result = new ToolsResponse();
        result.setHttpStatus(response.statusCode());
        result.setLatencyMs(latencyMs);
        result.setRawBody(raw);
        if (raw.isBlank()) {
            return result;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            extract(root, result);
        } catch (Exception ignored) {
            result.setParseFailed(true);
        }
        return result;
    }

    private void appendPayload(StringBuilder text, String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode content = node.get("content");
            if (content == null) {
                content = node.get("text");
            }
            if (content == null) {
                content = node.get("delta");
            }
            if (content != null && content.isTextual()) {
                text.append(content.asText());
                return;
            }
        } catch (Exception ignored) {
            // payload 不是合法 JSON 时按原文追加
        }
        text.append(payload);
    }

    private long extractTokens(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            JsonNode usage = node.get("usage");
            if (usage != null && usage.isObject()) {
                JsonNode total = usage.get("total_tokens");
                if (total == null) {
                    total = usage.get("totalTokens");
                }
                if (total != null && total.isNumber()) {
                    return total.asLong();
                }
            }
        } catch (Exception ignored) {
            // 忽略无法解析的 token 信息
        }
        return 0L;
    }

    private void extract(JsonNode node, ToolsResponse result) {
        if (result.getTraceId() == null) {
            JsonNode traceId = node.get("traceId");
            if (traceId != null && traceId.isTextual()) {
                result.setTraceId(traceId.asText());
            }
        }
        JsonNode iterations = node.get("totalIterations");
        if (iterations != null && iterations.isNumber()) {
            result.setTotalIterations(iterations.asInt());
        }
        JsonNode tokens = node.get("totalTokens");
        if (tokens != null && tokens.isNumber()) {
            result.setTotalTokens(tokens.asLong());
        }
        JsonNode answer = node.get("answer");
        if (answer != null && answer.isTextual()) {
            result.setAnswer(answer.asText());
        }
        if (node.isObject()) {
            JsonNode nameNode = node.get("name");
            if (nameNode == null) {
                nameNode = node.get("toolName");
            }
            if (nameNode == null) {
                nameNode = node.get("tool");
            }
            if (nameNode != null && nameNode.isTextual() && !nameNode.asText().isBlank()) {
                String name = nameNode.asText();
                if (result.getToolCalls().stream().noneMatch(t -> name.equals(t.getName()))) {
                    EvalResult.ToolCall toolCall = new EvalResult.ToolCall();
                    toolCall.setName(name);
                    JsonNode args = node.get("arguments");
                    if (args != null && args.isTextual()) {
                        toolCall.setArguments(args.asText());
                    }
                    JsonNode status = node.get("status");
                    if (status != null && status.isTextual()) {
                        toolCall.setResultStatus(status.asText());
                    }
                    result.getToolCalls().add(toolCall);
                }
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                extract(fields.next().getValue(), result);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                extract(child, result);
            }
        }
    }

    public record ChatResponse(int httpStatus, String text, long firstTokenMs, long latencyMs, long totalTokens) {
    }

    public static class ToolsResponse {
        private int httpStatus;
        private long latencyMs;
        private String rawBody = "";
        private String traceId;
        private int totalIterations;
        private long totalTokens;
        private String answer;
        private boolean parseFailed;
        private final java.util.List<EvalResult.ToolCall> toolCalls = new java.util.ArrayList<>();

        public int getHttpStatus() {
            return httpStatus;
        }

        public void setHttpStatus(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public long getLatencyMs() {
            return latencyMs;
        }

        public void setLatencyMs(long latencyMs) {
            this.latencyMs = latencyMs;
        }

        public String getRawBody() {
            return rawBody;
        }

        public void setRawBody(String rawBody) {
            this.rawBody = rawBody;
        }

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        public int getTotalIterations() {
            return totalIterations;
        }

        public void setTotalIterations(int totalIterations) {
            this.totalIterations = totalIterations;
        }

        public long getTotalTokens() {
            return totalTokens;
        }

        public void setTotalTokens(long totalTokens) {
            this.totalTokens = totalTokens;
        }

        public String getAnswer() {
            return answer;
        }

        public void setAnswer(String answer) {
            this.answer = answer;
        }

        public boolean isParseFailed() {
            return parseFailed;
        }

        public void setParseFailed(boolean parseFailed) {
            this.parseFailed = parseFailed;
        }

        public java.util.List<EvalResult.ToolCall> getToolCalls() {
            return toolCalls;
        }
    }
}