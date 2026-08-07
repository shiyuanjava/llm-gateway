package com.llm.gateway.ipcontrol;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IpBlockFilterTest {

    @Test
    void automaticBlockReturns429AndRetryAfter() throws Exception {
        IpBlockService service = mock(IpBlockService.class);
        when(service.evaluate("198.51.100.3"))
                .thenReturn(new IpBlockService.BlockDecision(
                        true,
                        "198.51.100.3",
                        IpBlockService.SOURCE_AUTO,
                        "too many requests",
                        LocalDateTime.now().plusMinutes(5)));
        IpBlockFilter filter = new IpBlockFilter(service, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.setRemoteAddr("198.51.100.3");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        assertThat(response.getContentAsString()).contains("ip_blocked");
    }

    @Test
    void manualBlockReturns403() throws Exception {
        IpBlockService service = mock(IpBlockService.class);
        when(service.evaluate("203.0.113.4"))
                .thenReturn(new IpBlockService.BlockDecision(
                        true, "203.0.113.4", IpBlockService.SOURCE_MANUAL, "manual", null));
        IpBlockFilter filter = new IpBlockFilter(service, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/chat/completions");
        request.setRemoteAddr("203.0.113.4");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("Retry-After")).isNull();
    }
}
