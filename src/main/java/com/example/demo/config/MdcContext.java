package com.example.demo.config;

import org.slf4j.MDC;

import java.util.Map;
import java.util.function.Supplier;

/**
 * 把请求线程的 MDC（traceId）传播到异步任务。
 * SSE 的 CompletableFuture 和工具的线程池不会自动继承 ThreadLocal。
 */
public final class MdcContext {

    private MdcContext() {
    }

    public static Runnable wrap(Runnable task) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            restore(captured);
            try {
                task.run();
            } finally {
                restore(previous);
            }
        };
    }

    public static <T> Supplier<T> wrapSupplier(Supplier<T> supplier) {
        Map<String, String> captured = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            restore(captured);
            try {
                return supplier.get();
            } finally {
                restore(previous);
            }
        };
    }

    private static void restore(Map<String, String> context) {
        if (context == null) {
            MDC.clear();
        } else {
            MDC.setContextMap(context);
        }
    }
}
