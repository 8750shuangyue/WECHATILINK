package com.example.demo.web;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 给历史 Map 响应补上统一信封字段，同时保留原有字段。
 * 用于 AI 对话/工具/护理工作流：这些接口的 payload 与 SSE、评测客户端强绑定，
 * 不能直接把字段挪进 data。
 */
public final class ApiResponses {

    private ApiResponses() {
    }

    public static Map<String, Object> enrich(Map<String, Object> payload) {
        Map<String, Object> result = payload == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(payload);
        boolean ok = Boolean.TRUE.equals(result.get("success"));
        result.putIfAbsent("code", ok ? 200 : 400);
        result.putIfAbsent("message", ok
                ? "操作成功"
                : String.valueOf(result.getOrDefault("error", "操作失败")));
        if (!ok) {
            result.putIfAbsent("error", result.get("message"));
        }
        return result;
    }
}
