package com.llm.gateway.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import com.llm.gateway.admin.web.AdminApiException;
import com.llm.gateway.admin.web.R;

import static org.assertj.core.api.Assertions.assertThat;

class AdminErrorContractTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void adminNotFoundUsesRContract() {
        MockHttpServletRequest request = request("/admin/pricing/999");

        var response = handler.handleAdminApi(AdminApiException.notFound("计费记录不存在"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isEqualTo(R.error(404, "计费记录不存在"));
    }

    @Test
    void unexpectedAdminFailureUsesRContract() {
        MockHttpServletRequest request = request("/admin/meta");

        var response = handler.handleUnexpected(new IllegalStateException("secret"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(R.error(500, "网关内部错误"));
    }

    @Test
    void unexpectedV1FailureKeepsOpenAiContract() {
        MockHttpServletRequest request = request("/v1/chat/completions");

        var response = handler.handleUnexpected(new IllegalStateException("secret"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isEqualTo(ErrorResponse.of("网关内部错误", "internal_error"));
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}
