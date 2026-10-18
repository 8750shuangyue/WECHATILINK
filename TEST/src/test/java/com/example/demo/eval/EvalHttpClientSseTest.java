package com.example.demo.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalHttpClientSseTest {

    private HttpServer server;
    private AtomicReference<String> requestBody;
    private AtomicReference<String> sseBody;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        requestBody = new AtomicReference<>();
        sseBody = new AtomicReference<>("");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/ai/chat/stream", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = sseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void separatesSourcesFromAnswerAndIgnoresUnknownEvents() throws Exception {
        sseBody.set(""
                + "event:rag-trace\n"
                + "data:{\"traceId\":\"trace-1\"}\n\n"
                + "event:rag-sources\n"
                + "data:{\"sources\":[{\"sourceId\":\"kb_a\",\"title\":\"标题 A\",\"rank\":1}]}\n\n"
                + "data:\"hello\"\n\n"
                + "event:future-event\n"
                + "data:{\"foo\":\"bar\"}\n\n"
                + "data:\" world\"\n\n"
                + "data:[DONE]\n\n");

        try (EvalHttpClient client = new EvalHttpClient(config(true), new ObjectMapper())) {
            EvalHttpClient.HttpResult result = client.chatStream("question");

            assertEquals("hello world", result.response());
            assertEquals("trace-1", result.traceId());
            assertEquals(1, result.sources().size());
            assertEquals("kb_a", result.sources().get(0).sourceId());
            assertEquals("标题 A", result.sources().get(0).title());
            assertEquals(1, result.sources().get(0).rank());
            assertEquals("available", result.sourcesStatus());
            assertTrue(result.firstTokenMs() >= 0);
            assertFalse(result.response().contains("sources"));
            assertFalse(result.response().contains("foo"));

            String sent = requestBody.get();
            assertTrue(sent.contains("\"includeSources\":\"true\""));
            assertTrue(sent.contains("\"includeCitations\":\"true\""));
        }
    }

    @Test
    void missingSourcesEventLeavesSourcesUnavailable() throws Exception {
        sseBody.set(""
                + "data:\"hello\"\n\n"
                + "data:[DONE]\n\n");

        try (EvalHttpClient client = new EvalHttpClient(config(true), new ObjectMapper())) {
            EvalHttpClient.HttpResult result = client.chatStream("question");

            assertEquals("hello", result.response());
            assertTrue(result.sources().isEmpty());
            assertEquals("unavailable", result.sourcesStatus());
        }
    }

    private EvalConfig config(boolean includeCitations) {
        return new EvalConfig(
                false,
                baseUrl,
                baseUrl,
                "user",
                "password",
                Path.of("eval/ai-eval-smoke-12.jsonl"),
                Path.of("eval/results"),
                0,
                200_000L,
                0,
                Duration.ofSeconds(10),
                0.75,
                List.of(),
                "test-model",
                0.3,
                5,
                0.5,
                "test-embedding",
                "test-commit",
                0.0,
                includeCitations,
                5,
                0.0,
                0.0,
                0.0,
                0.0
        );
    }
}
