package com.example.demo.push;

import com.sun.net.httpserver.HttpServer;
import org.asynchttpclient.AsyncHttpClient;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.asynchttpclient.Response;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AsyncHttpClientTransportTest {

    @Test
    void asyncHttpClientWorksOnNetty4Runtime() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ping", exchange -> {
            byte[] body = "pong".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        try (AsyncHttpClient client = new DefaultAsyncHttpClient()) {
            Response response = client
                    .prepareGet("http://127.0.0.1:" + server.getAddress().getPort() + "/ping")
                    .execute()
                    .get(10, TimeUnit.SECONDS);

            assertEquals(200, response.getStatusCode());
            assertEquals("pong", response.getResponseBody());
        } finally {
            server.stop(0);
        }
    }
}
