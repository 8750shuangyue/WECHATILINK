package com.example.demo.monitor;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MetricsServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MetricsService metrics = new MetricsService(registry);

    @Test
    void recordsToolCallCountersAndTimers() {
        metrics.recordToolCall("getWeather", true, 12);
        metrics.recordToolCall("getWeather", false, 30);

        assertEquals(1.0, registry.get("ai.tool.calls")
                .tags("tool", "getWeather", "status", "success").counter().count());
        assertEquals(1.0, registry.get("ai.tool.calls")
                .tags("tool", "getWeather", "status", "error").counter().count());
        assertNotNull(registry.find("ai.tool.duration").tag("tool", "getWeather").timer());
    }

    @Test
    void recordsRetrievalAndRerank() {
        metrics.recordRetrieval(42, true);
        metrics.recordRetrieval(18, false);

        assertEquals(2L, registry.get("rag.retrieval.duration").timer().count());
        assertEquals(1.0, registry.get("rag.rerank.applied").counter().count());
    }

    @Test
    void recordsExternalCallsByServiceAndOperation() {
        metrics.recordExternalCall("dashscope", "rerank", false, 80);

        assertEquals(1.0, registry.get("external.calls").tags(
                "service", "dashscope", "operation", "rerank", "status", "error").counter().count());
        assertNotNull(registry.find("external.duration")
                .tags("service", "dashscope", "operation", "rerank").timer());
    }
}
