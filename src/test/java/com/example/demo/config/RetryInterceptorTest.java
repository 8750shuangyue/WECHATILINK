package com.example.demo.config;

import com.sun.net.httpserver.HttpServer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RetryInterceptorTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger getCalls = new AtomicInteger();
    private final AtomicInteger postCalls = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/flaky", exchange -> {
            int call = getCalls.incrementAndGet();
            byte[] body = ("call-" + call).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(call == 1 ? 500 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/post", exchange -> {
            postCalls.incrementAndGet();
            byte[] body = "still-failing".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private OkHttpClient client() {
        return new OkHttpClient.Builder()
                .addInterceptor(new RetryInterceptor(1, 0))
                .build();
    }

    @Test
    void retriesIdempotentGetOnServerError() throws Exception {
        Request request = new Request.Builder().url(baseUrl + "/flaky").get().build();

        try (Response response = client().newCall(request).execute()) {
            assertEquals(200, response.code());
        }
        assertEquals(2, getCalls.get());
    }

    @Test
    void doesNotRetryPost() throws Exception {
        Request request = new Request.Builder()
                .url(baseUrl + "/post")
                .post(RequestBody.create("payload", MediaType.parse("text/plain")))
                .build();

        try (Response response = client().newCall(request).execute()) {
            assertEquals(500, response.code());
        }
        assertEquals(1, postCalls.get());
    }
}
