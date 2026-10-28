package com.example.demo.audit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 为每个请求生成 traceId，放入 MDC 并回写 X-Trace-Id，
 * 便于把 app.log、operation_audit_log 与具体请求对上。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class AuditContextFilter extends OncePerRequestFilter {

    public static final String MDC_TRACE_ID = "traceId";
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    private static final Pattern INCOMING_TRACE = Pattern.compile("[A-Za-z0-9-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        MDC.put(MDC_TRACE_ID, traceId);
        response.setHeader(HEADER_TRACE_ID, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_TRACE_ID);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(HEADER_TRACE_ID);
        if (incoming != null && INCOMING_TRACE.matcher(incoming.trim()).matches()) {
            return incoming.trim();
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
