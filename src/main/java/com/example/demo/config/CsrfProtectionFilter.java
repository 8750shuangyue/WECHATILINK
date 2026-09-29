package com.example.demo.config;

import com.alibaba.fastjson2.JSON;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Session 接口的跨站请求防护。
 *
 * 浏览器发出的非安全请求会携带 Origin；旧客户端可能只带 Referer；
 * 非浏览器 API 客户端两者都没有时保持兼容。SameSite=Lax 仍是第一层防护。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class CsrfProtectionFilter extends OncePerRequestFilter {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final Set<String> allowedOrigins;

    public CsrfProtectionFilter(
            @Value("${app.cors.allowed-origins:http://localhost:8080,http://127.0.0.1:8080}")
            String allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins.split(","))
                .map(this::normalizeOrigin)
                .filter(origin -> origin != null)
                .collect(Collectors.toUnmodifiableSet());
        if (this.allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("app.cors.allowed-origins must contain valid origins");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))
                || !request.getRequestURI().startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }

        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank()) {
            origin = request.getHeader("Referer");
        }

        if (origin == null || origin.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        String normalizedOrigin = normalizeOrigin(origin);
        if (normalizedOrigin == null || !allowedOrigins.contains(normalizedOrigin)) {
            reject(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private String normalizeOrigin(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            URI uri = new URI(value.trim());
            if (!uri.isAbsolute() || uri.getHost() == null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            int port = uri.getPort();
            if (port < 0) {
                if ("http".equals(scheme)) {
                    port = 80;
                } else if ("https".equals(scheme)) {
                    port = 443;
                }
            }
            return scheme + "://" + host + ":" + port;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        Map<String, Object> body = new HashMap<>();
        body.put("code", HttpServletResponse.SC_FORBIDDEN);
        body.put("message", "请求来源校验失败");
        body.put("data", null);
        response.getWriter().write(JSON.toJSONString(body));
    }
}
