package com.llm.gateway.audit;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.llm.gateway.auth.admin.AdminJwtFilter;
import com.llm.gateway.auth.admin.AdminPrincipal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminAuditFilterSanitizationTest {

    @Test
    void redactsCredentialFieldsAndApiKeyLiterals() throws Exception {
        AdminAuditService auditService = mock(AdminAuditService.class);
        AdminAuditFilter filter = new AdminAuditFilter(auditService);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/admin/api-keys/1");
        request.setRequestURI("/admin/api-keys/1");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(AdminJwtFilter.ADMIN_PRINCIPAL_ATTRIBUTE, new AdminPrincipal("admin"));
        request.setContent(("{\"password\":\"password-secret\","
                        + "\"key\":\"sk-top-secret\","
                        + "\"authorization\":\"Bearer jwt-secret\","
                        + "\"keyHash\":\"hash-secret\","
                        + "\"description\":\"sk-embedded-secret\"}")
                .getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (wrapped, ignored) -> wrapped.getInputStream()
                .readAllBytes());

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService)
                .record(eq("admin"), eq("UPDATE"), eq("api-keys/1"), detail.capture(), anyString(), eq(200));
        assertThat(detail.getValue())
                .doesNotContain("password-secret", "sk-top-secret", "jwt-secret", "hash-secret", "sk-embedded-secret")
                .contains("\"password\":\"***\"", "\"key\":\"***\"");
    }
}
