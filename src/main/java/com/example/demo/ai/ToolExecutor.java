package com.example.demo.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.example.demo.monitor.MetricsService;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 工具执行器：参数转换、反射调用、结果转换与指标记录。不参与对话循环。 */
@Slf4j
final class ToolExecutor {

    private final ToolRegistry registry;
    private volatile MetricsService metricsService;

    ToolExecutor(ToolRegistry registry) {
        this.registry = registry;
    }

    /** Micrometer 在 Spring 装配后才可用，单测可以为 null。 */
    void setMetricsService(MetricsService metricsService) {
        this.metricsService = metricsService;
    }

    String executeTool(String toolName, JSONObject arguments) {
        long startedAt = System.nanoTime();
        boolean success = false;
        ToolInfo toolInfo = registry.get(toolName);
        if (toolInfo == null) {
            log.warn("Unknown tool: {}", toolName);
            return "错误：未知工具 " + toolName;
        }

        try {
            Parameter[] paramTypes = toolInfo.method.getParameters();
            Object[] args = new Object[paramTypes.length];

            for (int i = 0; i < paramTypes.length; i++) {
                String paramName = paramTypes[i].getName();
                Class<?> paramType = paramTypes[i].getType();

                if (arguments != null && arguments.containsKey(paramName)) {
                    args[i] = convertArgument(arguments.get(paramName), paramType);
                } else {
                    args[i] = getDefaultValue(paramType);
                }
            }

            toolInfo.method.setAccessible(true);
            Object result = toolInfo.method.invoke(toolInfo.target, args);
            String converted = convertResult(result);
            success = true;
            return converted;

        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Tool execution failed: {}", toolName, cause);
            throw new RuntimeException(cause.getMessage(), cause);
        } finally {
            MetricsService metrics = metricsService;
            if (metrics != null) {
                metrics.recordToolCall(toolName, success,
                        (System.nanoTime() - startedAt) / 1_000_000);
            }
        }
    }

    List<Path> extractGeneratedFiles(String result) {
        List<Path> files = new ArrayList<>();
        if (result == null) return files;

        try {
            if (result.startsWith("[IMAGE:") && result.contains("]")) {
                int end = result.indexOf("]");
                String path = result.substring(7, end);
                files.add(Path.of(path));
                log.info("Extracted image file: {}", path);
            }
            if (result.startsWith("[AUDIO:") && result.contains("]")) {
                int end = result.indexOf("]");
                String path = result.substring(7, end);
                files.add(Path.of(path));
                log.info("Extracted audio file: {}", path);
            }
        } catch (Exception e) {
            log.warn("Failed to extract generated files from result");
        }

        return files;
    }

    private Object convertArgument(Object value, Class<?> targetType) {
        if (value == null) return getDefaultValue(targetType);

        if (targetType == String.class) {
            return value.toString();
        } else if (targetType == int.class || targetType == Integer.class) {
            return Integer.parseInt(value.toString());
        } else if (targetType == long.class || targetType == Long.class) {
            return Long.parseLong(value.toString());
        } else if (targetType == double.class || targetType == Double.class) {
            return Double.parseDouble(value.toString());
        } else if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.parseBoolean(value.toString());
        }
        return value;
    }

    private Object getDefaultValue(Class<?> type) {
        if (type == String.class) return "";
        if (type == int.class || type == Integer.class) return 0;
        if (type == long.class || type == Long.class) return 0L;
        if (type == double.class || type == Double.class) return 0.0;
        if (type == boolean.class || type == Boolean.class) return false;
        return null;
    }

    private String convertResult(Object result) {
        if (result == null) return "";
        if (result instanceof String) return (String) result;
        return JSON.toJSONString(result);
    }
}
