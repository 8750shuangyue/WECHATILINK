package com.example.demo.audit;

import jakarta.servlet.http.HttpServletRequest;

import java.util.regex.Pattern;

/**
 * 解析客户端 IP。生产环境应用只监听 127.0.0.1，请求必然经过 Nginx：
 * Nginx 会把 X-Real-IP 覆盖为真实来源地址，因此优先使用它。
 * 没有时取 X-Forwarded-For 的最后一个地址（离我们最近的代理），避免采用客户端可伪造的首个值。
 */
public final class ClientIpResolver {

    private static final int MAX_LENGTH = 64;
    private static final Pattern IP_PATTERN = Pattern.compile("[0-9a-fA-F:.]+");

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }

        String realIp = normalize(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            String[] parts = forwarded.split(",");
            for (int i = parts.length - 1; i >= 0; i--) {
                String candidate = normalize(parts[i]);
                if (candidate != null) {
                    return candidate;
                }
            }
        }

        String remote = normalize(request.getRemoteAddr());
        return remote == null ? "unknown" : remote;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH || !IP_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }
}
