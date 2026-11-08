package com.example.demo.core;

/**
 * 日志脱敏工具：用户消息、提示词、工具参数只记录长度，不落原文。
 * 需要定位具体请求时用 traceId，而不是把内容写进日志。
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /** 用户消息 / 提示词 / 查询词：只保留长度。 */
    public static String describe(String value) {
        return value == null ? "null" : "len=" + value.length();
    }

    /** 工具参数 JSON：只保留长度，避免把参数值写进日志。 */
    public static String describeArgs(String json) {
        return describe(json);
    }

    /** 密钥类配置：只说明是否配置，不输出任何片段。 */
    public static String secretState(String secret) {
        return secret == null || secret.isBlank() ? "unset" : "set(len=" + secret.length() + ")";
    }
}
