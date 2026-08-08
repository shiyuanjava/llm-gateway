package com.llm.gateway.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.llm.gateway.auth.admin.AdminJwtFilter;
import com.llm.gateway.auth.admin.AdminPrincipal;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class AdminAuditFilterTest {

    @Test
    void metaReloadIsAuditedAsReload() throws Exception {
        AdminAuditService auditService = mock(AdminAuditService.class);
        AdminAuditFilter filter = new AdminAuditFilter(auditService);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/admin/meta/reload");
        request.setRequestURI("/admin/meta/reload");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(AdminJwtFilter.ADMIN_PRINCIPAL_ATTRIBUTE, new AdminPrincipal("admin"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(auditService).record(eq("admin"), eq("RELOAD"), eq("meta/reload"), isNull(), anyString(), eq(200));
    }
}
