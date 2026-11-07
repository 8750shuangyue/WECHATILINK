package com.example.demo.push;

import nl.martijndwars.webpush.Utils;
import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：web-push 5.1.2 用 URL-safe Base64 解码 VAPID 密钥。
 * 之前代码把密钥转成标准 Base64（引入 +//），启动即报 Illegal base64 character 2f。
 */
class WebPushVapidKeyTest {

    @Test
    void urlSafeVapidKeysEnablePushService() throws Exception {
        Security.addProvider(new BouncyCastleProvider());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("ECDH", "BC");
        generator.initialize(new ECGenParameterSpec("prime256v1"), new SecureRandom());
        KeyPair keyPair = generator.generateKeyPair();

        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String publicKey = encoder.encodeToString(Utils.encode((ECPublicKey) keyPair.getPublic()));
        String privateKey = encoder.encodeToString(Utils.encode((ECPrivateKey) keyPair.getPrivate()));

        WebPushService service = new WebPushService(null, publicKey, privateKey, "mailto:test@example.com");

        Field enabled = WebPushService.class.getDeclaredField("enabled");
        enabled.setAccessible(true);
        assertTrue((boolean) enabled.get(service), "URL-safe VAPID 密钥应能初始化推送服务");
    }
}
