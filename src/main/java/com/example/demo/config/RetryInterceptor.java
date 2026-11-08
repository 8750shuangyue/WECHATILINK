package com.example.demo.config;

import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;

/**
 * 只对幂等请求（GET/HEAD）做有限重试：网络异常或 5xx。
 * POST 一律不自动重试，避免重复生图、重复扣费或重复写数据。
 */
public class RetryInterceptor implements Interceptor {

    private final int maxRetries;
    private final long backoffMs;

    public RetryInterceptor(int maxRetries, long backoffMs) {
        this.maxRetries = Math.max(0, maxRetries);
        this.backoffMs = Math.max(0, backoffMs);
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
        Request request = chain.request();
        boolean idempotent = isIdempotent(request.method());
        int attempts = idempotent ? maxRetries + 1 : 1;
        IOException lastFailure = null;

        for (int i = 0; i < attempts; i++) {
            try {
                Response response = chain.proceed(request);
                if (idempotent && response.code() >= 500 && i + 1 < attempts) {
                    response.close();
                    sleepBackoff();
                    continue;
                }
                return response;
            } catch (IOException e) {
                lastFailure = e;
                if (!idempotent || i + 1 >= attempts) {
                    throw e;
                }
                sleepBackoff();
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("request failed");
    }

    private static boolean isIdempotent(String method) {
        return "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
    }

    private void sleepBackoff() {
        if (backoffMs <= 0) {
            return;
        }
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
