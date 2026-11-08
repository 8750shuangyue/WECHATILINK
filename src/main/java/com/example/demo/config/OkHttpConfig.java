package com.example.demo.config;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 全局出站 HTTP 客户端：统一连接池、超时与幂等重试策略。
 * 各服务可在共享客户端上 newBuilder() 覆盖单次超时，连接池与调度器仍然共用。
 */
@Configuration
public class OkHttpConfig {

    @Value("${http.client.connect-timeout-ms:5000}")
    private long connectTimeoutMs;

    @Value("${http.client.read-timeout-ms:10000}")
    private long readTimeoutMs;

    @Value("${http.client.write-timeout-ms:5000}")
    private long writeTimeoutMs;

    @Value("${http.client.pool-max-idle:5}")
    private int poolMaxIdle;

    @Value("${http.client.pool-keep-alive-minutes:5}")
    private long poolKeepAliveMinutes;

    @Value("${http.client.retry-max:1}")
    private int retryMax;

    @Value("${http.client.retry-backoff-ms:200}")
    private long retryBackoffMs;

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(Math.max(1, connectTimeoutMs), TimeUnit.MILLISECONDS)
                .readTimeout(Math.max(1, readTimeoutMs), TimeUnit.MILLISECONDS)
                .writeTimeout(Math.max(1, writeTimeoutMs), TimeUnit.MILLISECONDS)
                .connectionPool(new ConnectionPool(Math.max(1, poolMaxIdle),
                        Math.max(1, poolKeepAliveMinutes), TimeUnit.MINUTES))
                .addInterceptor(new RetryInterceptor(retryMax, retryBackoffMs))
                .retryOnConnectionFailure(true)
                .build();
    }
}
