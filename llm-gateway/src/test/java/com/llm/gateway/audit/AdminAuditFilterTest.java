package com.llm.gateway.audit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.llm.gateway.persistence.entity.AdminAuditLogEntity;
import com.llm.gateway.persistence.mapper.AdminAuditLogMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

@SpringBootTest(
        properties = {
            "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
            "gateway.admin.bootstrap-username=it-admin",
            "gateway.admin.bootstrap-password=it-admin-pass"
        })
@AutoConfigureMockMvc
class AdminAuditFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminAuditLogMapper auditMapper;

    @Test
    void anonymousWriteIsNotAudited() throws Exception {
        long before = auditMapper.selectCount(
                Wrappers.<AdminAuditLogEntity>lambdaQuery().eq(AdminAuditLogEntity::getAction, "UPDATE"));
        // 无 token，被 JWT 过滤器 401 拦截，审计过滤器不应记录（无可信身份）
        mockMvc.perform(put("/admin/pricing/999999")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"model\":\"x\"}"));
        long after = auditMapper.selectCount(
                Wrappers.<AdminAuditLogEntity>lambdaQuery().eq(AdminAuditLogEntity::getAction, "UPDATE"));
        assertThat(after).isEqualTo(before);
    }
}
