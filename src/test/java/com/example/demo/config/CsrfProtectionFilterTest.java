package com.example.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class CsrfProtectionFilterTest {

    private final CsrfProtectionFilter filter =
            new CsrfProtectionFilter("http://localhost:8080");

    @Test
    void allowsStateChangingRequestFromConfiguredOrigin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.addHeader("Origin", "http://localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void allowsStateChangingRequestFromConfiguredReferer() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/items/1");
        request.addHeader("Referer", "http://localhost:8080/inventory");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void allowsStateChangingRequestFromSameOriginNotInAllowlist() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setScheme("http");
        request.setServerName("101.37.254.73");
        request.setServerPort(8080);
        request.addHeader("Origin", "http://101.37.254.73:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void allowsSameOriginRefererWithDefaultPort() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setScheme("http");
        request.setServerName("example.test");
        request.setServerPort(80);
        request.addHeader("Referer", "http://example.test/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void rejectsStateChangingRequestFromUnknownOrigin() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(chain.getRequest());
    }

    @Test
    void keepsNonBrowserClientsCompatibleWhenOriginHeadersAreAbsent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void doesNotBlockSafeReadRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat/history");
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertNotNull(chain.getRequest());
    }
}
