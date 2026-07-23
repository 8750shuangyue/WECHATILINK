package com.example.wechat.bot.core.service;

import com.example.wechat.bot.core.model.dto.ChatApiStatus;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChatApiStatsService {

    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong failureCount = new AtomicLong();
    private final AtomicLong lastSuccessAt = new AtomicLong();
    private final AtomicLong lastFailureAt = new AtomicLong();
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();

    public void recordSuccess() {
        successCount.incrementAndGet();
        lastSuccessAt.set(System.currentTimeMillis());
    }

    public void recordFailure(String message) {
        failureCount.incrementAndGet();
        lastFailureAt.set(System.currentTimeMillis());
        if (message != null && !message.isBlank()) {
            lastErrorMessage.set(message);
        }
    }

    public ChatApiStatus getStatusSnapshot() {
        long okAt = lastSuccessAt.get();
        long failAt = lastFailureAt.get();
        boolean lastCallOk = okAt >= failAt && okAt > 0;
        return new ChatApiStatus(
                successCount.get(),
                failureCount.get(),
                okAt,
                failAt,
                lastErrorMessage.get(),
                lastCallOk
        );
    }

    public void reset() {
        successCount.set(0);
        failureCount.set(0);
        lastSuccessAt.set(0);
        lastFailureAt.set(0);
        lastErrorMessage.set(null);
    }
}
