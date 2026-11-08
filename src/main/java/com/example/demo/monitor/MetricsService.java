package com.example.demo.monitor;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 业务指标统一入口。标签值必须低基数，不能把用户输入直接当 tag。
 */
@Component
public class MetricsService {

    private final MeterRegistry registry;

    public MetricsService(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordToolCall(String toolName, boolean success, long durationMs) {
        String tool = safe(toolName);
        registry.counter("ai.tool.calls", "tool", tool, "status", success ? "success" : "error").increment();
        registry.timer("ai.tool.duration", "tool", tool).record(duration(durationMs));
    }

    public void recordRetrieval(long durationMs, boolean rerankApplied) {
        registry.timer("rag.retrieval.duration").record(duration(durationMs));
        if (rerankApplied) {
            registry.counter("rag.rerank.applied").increment();
        }
    }

    public void recordRerankFallback(String reason) {
        registry.counter("rag.rerank.fallback", "reason", safe(reason)).increment();
    }

    public void recordExternalCall(String service, String operation, boolean success, long durationMs) {
        String svc = safe(service);
        String op = safe(operation);
        registry.counter("external.calls", "service", svc, "operation", op,
                "status", success ? "success" : "error").increment();
        registry.timer("external.duration", "service", svc, "operation", op).record(duration(durationMs));
    }

    private static Duration duration(long millis) {
        return Duration.ofMillis(Math.max(0, millis));
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.length() > 64 ? value.substring(0, 64) : value;
    }
}
