package com.example.demo.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MdcContextTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void propagatesTraceIdIntoAsyncTaskWithoutPollutingCaller() throws Exception {
        MDC.put("traceId", "trace-abc");
        AtomicReference<String> seen = new AtomicReference<>();
        Runnable task = MdcContext.wrap(() -> seen.set(MDC.get("traceId")));
        MDC.clear();

        Thread thread = new Thread(task);
        thread.start();
        thread.join();

        assertEquals("trace-abc", seen.get());
        assertNull(MDC.get("traceId"));
    }

    @Test
    void supplierCapturesTraceIdAndRestoresCallerContext() {
        MDC.put("traceId", "trace-abc");
        Supplier<String> supplier = MdcContext.wrapSupplier(() -> MDC.get("traceId"));
        MDC.clear();

        assertEquals("trace-abc", supplier.get());
        assertNull(MDC.get("traceId"));
    }
}
