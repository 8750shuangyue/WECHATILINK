package com.example.demo.monitor;

import com.example.demo.chat.VectorStoreService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 向量索引是异步加载的；未就绪不把整个服务判为 DOWN，
 * 只把状态放进详情里，避免启动期被健康检查误重启。
 */
@Component
public class RagIndexHealthIndicator implements HealthIndicator {

    private final VectorStoreService vectorStoreService;

    public RagIndexHealthIndicator(VectorStoreService vectorStoreService) {
        this.vectorStoreService = vectorStoreService;
    }

    @Override
    public Health health() {
        boolean ready = vectorStoreService.isIndexReady();
        return Health.up()
                .withDetail("indexReady", ready)
                .withDetail("component", "rag-vector-store")
                .build();
    }
}
