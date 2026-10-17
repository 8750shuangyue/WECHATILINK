package com.example.demo.chat;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.config.DashScopeConfig;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddingServiceTest {

    private HttpServer server;
    private AtomicInteger requests;
    private AtomicReference<String> lastRequestBody;
    private volatile int responseStatus;
    private volatile String responseBody;
    private EmbeddingService service;

    @BeforeEach
    void setUp() throws IOException {
        requests = new AtomicInteger();
        lastRequestBody = new AtomicReference<>("");
        responseStatus = 200;
        responseBody = "";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            requests.incrementAndGet();
            lastRequestBody.set(new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();

        DashScopeConfig config = new DashScopeConfig();
        DashScopeConfig.Embedding embedding = config.getEmbedding();
        embedding.setApiKey("test-key");
        embedding.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        embedding.setModel("text-embedding-v2");
        embedding.setBatchEnabled(true);
        embedding.setBatchSize(10);
        embedding.setMaxAttempts(1);
        embedding.setRetryBackoffMs(0);
        embedding.setTimeoutSeconds(5);
        service = new EmbeddingService(config);
    }

    @AfterEach
    void tearDown() {
        service.close();
        server.stop(0);
    }

    @Test
    void batchRestoresResponseOrderByIndex() throws Exception {
        responseBody = "{\"data\":["
                + "{\"index\":1,\"embedding\":[0.2,0.3]},"
                + "{\"index\":0,\"embedding\":[0.1,0.2]}]}";

        List<float[]> embeddings = service.embedBatch(List.of("first", "second"));

        assertEquals(2, embeddings.size());
        assertArrayEquals(new float[]{0.1f, 0.2f}, embeddings.get(0), 1e-6f);
        assertArrayEquals(new float[]{0.2f, 0.3f}, embeddings.get(1), 1e-6f);
        JSONObject request = JSON.parseObject(lastRequestBody.get());
        assertEquals(2, request.getJSONArray("input").size());
        assertEquals(1, requests.get());
    }

    @Test
    void batchSplitsWhenWholeBatchFails() throws Exception {
        DashScopeConfig config = new DashScopeConfig();
        DashScopeConfig.Embedding embedding = config.getEmbedding();
        embedding.setApiKey("test-key");
        embedding.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        embedding.setModel("text-embedding-v2");
        embedding.setBatchSize(2);
        embedding.setMaxAttempts(1);
        embedding.setRetryBackoffMs(0);
        embedding.setTimeoutSeconds(5);
        EmbeddingService splittingService = new EmbeddingService(config);

        server.removeContext("/embeddings");
        server.createContext("/embeddings", exchange -> {
            requests.incrementAndGet();
            String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestBody.set(requestBody);
            JSONArray input = JSON.parseObject(requestBody).getJSONArray("input");
            int status = input.size() > 1 ? 500 : 200;
            String body = status == 200
                    ? "{\"data\":[{\"index\":0,\"embedding\":[1.0]}]}"
                    : "batch too large";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });

        try {
            List<float[]> embeddings = splittingService.embedBatch(List.of("first", "second"));
            assertEquals(2, embeddings.size());
            assertEquals(3, requests.get());
        } finally {
            splittingService.close();
        }
    }

    @Test
    void batchRetriesTransientStatus() throws Exception {
        DashScopeConfig config = new DashScopeConfig();
        DashScopeConfig.Embedding embedding = config.getEmbedding();
        embedding.setApiKey("test-key");
        embedding.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        embedding.setModel("text-embedding-v2");
        embedding.setMaxAttempts(2);
        embedding.setRetryBackoffMs(0);
        embedding.setTimeoutSeconds(5);
        EmbeddingService retryingService = new EmbeddingService(config);

        server.removeContext("/embeddings");
        server.createContext("/embeddings", exchange -> {
            int count = requests.incrementAndGet();
            int status = count == 1 ? 429 : 200;
            String body = status == 200
                    ? "{\"data\":[{\"index\":0,\"embedding\":[0.5]}]}"
                    : "rate limited";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });

        try {
            List<float[]> embeddings = retryingService.embedBatch(List.of("first"));
            assertEquals(1, embeddings.size());
            assertArrayEquals(new float[]{0.5f}, embeddings.get(0), 1e-6f);
            assertEquals(2, requests.get());
        } finally {
            retryingService.close();
        }
    }

    @Test
    void singleInputThrowsWhenResponseHasNoEmbedding() {
        responseBody = "{\"data\":[]}";

        assertThrows(IOException.class,
                () -> service.embedBatch(List.of("first")));
    }
}
