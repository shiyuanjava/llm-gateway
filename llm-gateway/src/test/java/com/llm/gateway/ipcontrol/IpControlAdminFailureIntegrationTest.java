package com.llm.gateway.ipcontrol;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llm.gateway.AdminTestTokens;
import com.llm.gateway.persistence.entity.AdminAuditLogEntity;
import com.llm.gateway.persistence.mapper.AdminAuditLogMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        properties = {
            "gateway.admin.jwt-secret=" + AdminTestTokens.TEST_SECRET,
            "gateway.admin.bootstrap-username=it-admin",
            "gateway.admin.bootstrap-password=it-admin-pass"
        })
@AutoConfigureMockMvc
class IpControlAdminFailureIntegrationTest {

    private static final long MISSING_BLOCK_ID = Long.MAX_VALUE;
    private static final String RESOURCE = "ip-control/blocks/" + MISSING_BLOCK_ID;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAuditLogMapper auditMapper;

    @BeforeEach
    void deleteExistingAuditRows() {
        deleteAuditRows();
    }

    @AfterEach
    void deleteCreatedAuditRows() {
        deleteAuditRows();
    }

    @Test
    void deletingMissingBlockReturnsNotFoundAndAuditsFailure() throws Exception {
        mockMvc.perform(delete("/admin/ip-control/blocks/{id}", MISSING_BLOCK_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + AdminTestTokens.issue()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.msg").value("IP 封禁记录不存在"));

        AdminAuditLogEntity audit = auditMapper.selectOne(
                Wrappers.<AdminAuditLogEntity>lambdaQuery().eq(AdminAuditLogEntity::getResource, RESOURCE));
        assertThat(audit).isNotNull();
        assertThat(audit.getAction()).isEqualTo("DELETE");
        assertThat(audit.getStatus()).isEqualTo(404);
    }

    private void deleteAuditRows() {
        auditMapper.delete(Wrappers.<AdminAuditLogEntity>lambdaQuery().eq(AdminAuditLogEntity::getResource, RESOURCE));
    }
}
