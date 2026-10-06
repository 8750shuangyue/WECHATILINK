package com.example.demo.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService();

    @Test
    void blocksAfterMaxFailures() {
        String key = service.buildKey("Alice", "127.0.0.1");

        for (int i = 0; i < 4; i++) {
            service.recordFailure(key);
        }
        assertFalse(service.isBlocked(key), "four failures should not be blocked yet");

        service.recordFailure(key);
        assertTrue(service.isBlocked(key), "fifth failure should block");
    }

    @Test
    void successClearsFailures() {
        String key = service.buildKey("bob", "10.0.0.1");
        for (int i = 0; i < 5; i++) {
            service.recordFailure(key);
        }
        assertTrue(service.isBlocked(key));

        service.recordSuccess(key);
        assertFalse(service.isBlocked(key), "successful login should clear the window");
    }

    @Test
    void unknownKeyIsNotBlocked() {
        assertFalse(service.isBlocked(service.buildKey("nobody", "127.0.0.1")));
    }

    @Test
    void keyIsCaseInsensitiveAndCaseFolded() {
        String upper = service.buildKey(" Alice_01 ", "127.0.0.1");
        String lower = service.buildKey("alice_01", "127.0.0.1");
        assertTrue(upper.equals(lower));
    }
}
