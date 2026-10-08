package com.example.demo.chat;

import com.example.demo.aicare.Result;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagObservabilityControllerTest {

    @Test
    void rejectsMissingSessionActorWithUnauthorized() {
        RagObservabilityService service = mock(RagObservabilityService.class);
        when(service.findByTraceId(null, "trace-1"))
                .thenReturn(RagObservabilityService.LookupResult.error(401, "未登录"));
        RagObservabilityController controller = new RagObservabilityController(service);

        ResponseEntity<Result<RagObservabilityService.RagRetrievalObservation>> response =
                controller.getByTraceId("trace-1", new MockHttpServletRequest());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertEquals(401, response.getBody().getCode());
        assertEquals("未登录", response.getBody().getMessage());
        verify(service).findByTraceId(null, "trace-1");
    }

    @Test
    void usesInterceptorUserNameAsActor() {
        RagObservabilityService service = mock(RagObservabilityService.class);
        when(service.findByTraceId("alice", "trace-1"))
                .thenReturn(RagObservabilityService.LookupResult.error(404, "未找到检索记录"));
        RagObservabilityController controller = new RagObservabilityController(service);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userName", "alice");

        ResponseEntity<Result<RagObservabilityService.RagRetrievalObservation>> response =
                controller.getByTraceId("trace-1", request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(service).findByTraceId("alice", "trace-1");
    }
}
