package com.llm.gateway.migration;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
        "gateway.admin.bootstrap-username=it-admin",
        "gateway.admin.bootstrap-password=it-admin-pass"
})
class ApiKeyHashMigrationTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ApiKeyHashMigration migration;

    @Test
    void rerunOnMigratedSchemaIsNoop() {
        // 上下文启动时已迁移(或本就是新结构);重复执行必须无害
        assertThatCode(() -> migration.migrate(jdbc)).doesNotThrowAnyException();
        assertThatCode(() -> migration.migrate(jdbc)).doesNotThrowAnyException();
    }
}
