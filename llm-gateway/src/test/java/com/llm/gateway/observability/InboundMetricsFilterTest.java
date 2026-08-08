package com.llm.gateway.observability;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class InboundMetricsFilterTest {

    @Test
    void countsRequestBeforeDownstreamFilterChainCompletes() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InboundMetricsFilter filter = new InboundMetricsFilter(new MetricsRecorder(registry));

        filter.doFilter(
                new MockHttpServletRequest("POST", "/v1/chat/completions"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(registry.counter("llm.gateway.requests.inbound").count()).isEqualTo(1.0);
    }

    @Test
    void doesNotCountCorsPreflightAsBusinessTraffic() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InboundMetricsFilter filter = new InboundMetricsFilter(new MetricsRecorder(registry));

        filter.doFilter(
                new MockHttpServletRequest("OPTIONS", "/v1/chat/completions"),
                new MockHttpServletResponse(),
                new MockFilterChain());

        assertThat(registry.counter("llm.gateway.requests.inbound").count()).isZero();
    }
}
