package com.example.demo.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesTraceIdAndClearsMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/home");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(TraceIdFilter.TRACE_ID)));

        assertNotNull(seen.get());
        assertEquals(32, seen.get().length());
        assertEquals(seen.get(), response.getHeader(TraceIdFilter.HEADER));
        assertNull(MDC.get(TraceIdFilter.TRACE_ID));
    }

    @Test
    void reusesSafeIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/home");
        request.addHeader(TraceIdFilter.HEADER, "trace-abc_123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(TraceIdFilter.TRACE_ID)));

        assertEquals("trace-abc_123", seen.get());
        assertEquals("trace-abc_123", response.getHeader(TraceIdFilter.HEADER));
    }

    @Test
    void rejectsUnsafeIncomingTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/home");
        request.addHeader(TraceIdFilter.HEADER, "bad id\nwith-injection");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(TraceIdFilter.TRACE_ID)));

        assertNotNull(seen.get());
        assertTrue(seen.get().matches("[0-9a-f]{32}"));
    }
}
