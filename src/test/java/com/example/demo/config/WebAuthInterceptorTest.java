package com.example.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebAuthInterceptorTest {

    private final WebAuthInterceptor interceptor = new WebAuthInterceptor();

    @Test
    void allowsWhitelistedApiWithoutSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals(200, response.getStatus());
    }

    @Test
    void allowsCorsPreflight() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void rejectsProtectedApiWithoutSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/history");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        String body = response.getContentAsString();
        assertTrue(body.contains("\"code\":401"));
        assertTrue(body.contains("未登录"));
    }

    @Test
    void allowsAuthenticatedRequestAndExposesUserName() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/history");
        request.getSession().setAttribute("user", "alice");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals("alice", request.getAttribute("userName"));
    }
}
