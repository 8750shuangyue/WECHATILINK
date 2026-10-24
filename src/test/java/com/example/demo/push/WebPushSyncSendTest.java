package com.example.demo.push;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebPushSyncSendTest {

    @Test
    void syncSendWorksWithoutAsyncHttpClientOnClasspath() throws Exception {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("org.asynchttpclient.AsyncHttpClient"));

        Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "BC");
        generator.initialize(new ECGenParameterSpec("prime256v1"));
        KeyPair vapidKeys = generator.generateKeyPair();
        KeyPair subscriptionKeys = generator.generateKeyPair();
        byte[] authSecret = new byte[16];
        new SecureRandom().nextBytes(authSecret);

        AtomicReference<Headers> requestHeaders = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            requestHeaders.set(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();

        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/push";
            PushService pushService = new PushService(vapidKeys, "mailto:test@example.com");
            Notification notification = new Notification(
                    endpoint, subscriptionKeys.getPublic(), authSecret,
                    "hello".getBytes(StandardCharsets.UTF_8));

            HttpResponse response = pushService.send(notification);

            assertEquals(201, response.getStatusLine().getStatusCode());
            assertTrue(requestHeaders.get().getFirst("Content-Encoding").startsWith("aes"));
            String authorization = requestHeaders.get().getFirst("Authorization");
            assertTrue(authorization != null
                            && (authorization.startsWith("WebPush ") || authorization.startsWith("vapid ")),
                    "auth=" + authorization);
        } finally {
            server.stop(0);
        }
    }
}
