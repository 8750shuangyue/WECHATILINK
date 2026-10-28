package com.example.demo.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OperationAuditServiceTest {

    private final OperationAuditWriter writer = mock(OperationAuditWriter.class);
    private final Executor direct = Runnable::run;

    @Test
    void recordsUserIpAndAction() {
        OperationAuditService service = new OperationAuditService(writer, direct, true, 1000);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setAttribute("userName", "alice");
        request.addHeader("X-Real-IP", "203.0.113.7");
        request.addHeader("User-Agent", "JUnit");

        service.record(request, AuditAction.AUTH_LOGIN, AuditAction.SUCCESS, "user", "alice", null);

        var captor = org.mockito.ArgumentCaptor.forClass(AuditRecord.class);
        verify(writer).write(captor.capture());
        AuditRecord record = captor.getValue();
        assertEquals("alice", record.userId());
        assertEquals("203.0.113.7", record.clientIp());
        assertEquals(AuditAction.AUTH_LOGIN, record.action());
        assertEquals("/api/auth/login", record.requestPath());
    }

    @Test
    void usesLastForwardedForValueWhenRealIpMissing() {
        OperationAuditService service = new OperationAuditService(writer, direct, true, 1000);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kb/list");
        request.addHeader("X-Forwarded-For", "6.6.6.6, 198.51.100.9");

        service.record(request, "TEST", "SUCCESS", null, null, null);

        var captor = org.mockito.ArgumentCaptor.forClass(AuditRecord.class);
        verify(writer).write(captor.capture());
        assertEquals("198.51.100.9", captor.getValue().clientIp());
    }

    @Test
    void truncatesAndSanitizesDetail() {
        OperationAuditService service = new OperationAuditService(writer, direct, true, 10);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/test");

        service.record(request, "TEST", "FAILURE", null, null, "line1\nline2\nline3");

        var captor = org.mockito.ArgumentCaptor.forClass(AuditRecord.class);
        verify(writer).write(captor.capture());
        assertEquals(10, captor.getValue().detail().length());
        assertTrue(captor.getValue().detail().indexOf('\n') < 0);
    }

    @Test
    void doesNothingWhenDisabled() {
        OperationAuditService service = new OperationAuditService(writer, direct, false, 1000);
        MockHttpServletRequest request = new MockHttpServletRequest();

        service.record(request, "TEST", "SUCCESS", null, null, null);

        verify(writer, never()).write(any());
    }
}
