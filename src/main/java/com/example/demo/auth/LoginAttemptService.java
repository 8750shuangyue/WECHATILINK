package com.example.demo.auth;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration ATTEMPT_WINDOW = Duration.ofMinutes(15);
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final Map<String, AttemptWindow> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String key) {
        AttemptWindow window = attempts.get(key);
        if (window == null) {
            return false;
        }

        synchronized (window) {
            if (window.isExpired(Instant.now())) {
                attempts.remove(key, window);
                return false;
            }
            return window.failures >= MAX_ATTEMPTS;
        }
    }

    public void recordFailure(String key) {
        Instant now = Instant.now();
        AttemptWindow window = attempts.compute(key, (ignored, current) -> {
            if (current == null || current.isExpired(now)) {
                return new AttemptWindow(now);
            }
            synchronized (current) {
                current.failures++;
                current.lastFailure = now;
                return current;
            }
        });

        if (attempts.size() > CLEANUP_THRESHOLD) {
            removeExpiredEntries(now);
        }
    }

    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    public String buildKey(String userName, String remoteAddress) {
        String normalizedUser = userName == null ? "" : userName.trim().toLowerCase(Locale.ROOT);
        String normalizedAddress = remoteAddress == null ? "" : remoteAddress.trim();
        return normalizedAddress + "|" + normalizedUser;
    }

    private void removeExpiredEntries(Instant now) {
        attempts.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private static final class AttemptWindow {
        private final Instant firstFailure;
        private Instant lastFailure;
        private int failures;

        private AttemptWindow(Instant now) {
            this.firstFailure = now;
            this.lastFailure = now;
            this.failures = 1;
        }

        private boolean isExpired(Instant now) {
            return lastFailure.plus(ATTEMPT_WINDOW).isBefore(now)
                    || firstFailure.plus(ATTEMPT_WINDOW).isBefore(now);
        }
    }
}
