package com.llm.gateway.observability;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TraceIdFilter:MDC 写入与 finally 清理、响应头回写、外部合法值透传、非法值替换。
 */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void generatesTraceIdWritesMdcAndHeaderThenCleansUp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/chat/completions");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(inChain.get()).isNotBlank().hasSize(16);
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo(inChain.get());
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull(); // finally 已清理
    }

    @Test
    void propagatesValidIncomingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/chat/completions");
        request.addHeader(TraceIdFilter.HEADER, "client-abc_123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(inChain.get()).isEqualTo("client-abc_123");
        assertThat(response.getHeader(TraceIdFilter.HEADER)).isEqualTo("client-abc_123");
    }

    @Test
    void replacesIllegalIncomingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/chat/completions");
        request.addHeader(TraceIdFilter.HEADER, "bad id<script>");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> inChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> inChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        assertThat(inChain.get()).isNotEqualTo("bad id<script>").hasSize(16);
    }
}
