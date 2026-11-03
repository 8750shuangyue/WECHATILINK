package com.example.demo.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(self), geolocation=(self)");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        // 页面入口强制协商缓存，避免发版后浏览器继续使用旧 HTML；静态资源与接口不受影响。
        String uri = request.getRequestURI();
        if (uri != null
                && !uri.startsWith("/api/")
                && !uri.startsWith("/uploads/")
                && !uri.startsWith("/css/")
                && !uri.startsWith("/js/")
                && !uri.startsWith("/images/")
                && !uri.startsWith("/error")) {
            response.setHeader("Cache-Control", "no-cache, must-revalidate");
        }
        filterChain.doFilter(request, response);
    }
}
