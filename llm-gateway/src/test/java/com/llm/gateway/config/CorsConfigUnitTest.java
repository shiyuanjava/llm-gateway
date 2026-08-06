package com.llm.gateway.config;

import java.util.List;

import jakarta.servlet.ServletException;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

import static org.assertj.core.api.Assertions.assertThat;

class CorsConfigUnitTest {

    private static final String ORIGIN = "http://localhost:5173";

    @Test
    void allowsAdminAndPlaygroundPreflightAndExposesRequestId() throws Exception {
        CorsFilter filter = filter(List.of(ORIGIN));

        MockHttpServletResponse admin = preflight(filter, "/admin/api-keys", "GET", "authorization,x-request-id");
        assertThat(admin.getStatus()).isEqualTo(200);
        assertThat(admin.getHeader("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);

        MockHttpServletResponse playground =
                preflight(filter, "/v1/chat/completions", "POST", "authorization,content-type,x-request-id");
        assertThat(playground.getStatus()).isEqualTo(200);
        assertThat(playground.getHeader("Access-Control-Allow-Origin")).isEqualTo(ORIGIN);
        assertThat(playground.getHeader("Access-Control-Expose-Headers")).contains("X-Request-Id");
        assertThat(playground.getHeader("Access-Control-Max-Age")).isEqualTo("3600");
    }

    @Test
    void rejectsUnknownOriginAndKeepsEmptyWhitelistClosed() throws Exception {
        CorsFilter configured = filter(List.of(ORIGIN));
        MockHttpServletRequest disallowed = new MockHttpServletRequest("GET", "/admin/api-keys");
        disallowed.addHeader("Origin", "http://evil.example");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        configured.doFilter(disallowed, rejected, new MockFilterChain());
        assertThat(rejected.getStatus()).isEqualTo(403);
        assertThat(rejected.getHeader("Access-Control-Allow-Origin")).isNull();

        MockHttpServletResponse closed = preflight(filter(List.of()), "/v1/chat/completions", "POST", "authorization");
        assertThat(closed.getHeader("Access-Control-Allow-Origin")).isNull();
    }

    private static CorsFilter filter(List<String> origins) {
        return new CorsConfig()
                .gatewayCorsFilter(new CorsProperties(origins, 3600))
                .getFilter();
    }

    private static MockHttpServletResponse preflight(
            CorsFilter filter, String path, String method, String requestedHeaders)
            throws java.io.IOException, ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
        request.addHeader("Origin", ORIGIN);
        request.addHeader("Access-Control-Request-Method", method);
        request.addHeader("Access-Control-Request-Headers", requestedHeaders);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }
}
