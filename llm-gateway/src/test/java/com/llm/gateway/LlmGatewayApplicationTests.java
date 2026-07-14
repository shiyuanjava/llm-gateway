package com.llm.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        properties = {
            "gateway.admin.jwt-secret=test-secret-0123456789abcdef0123456789abcdef",
            "gateway.admin.bootstrap-username=admin",
            "gateway.admin.bootstrap-password=admin123"
        })
class LlmGatewayApplicationTests {

    @Test
    void contextLoads() {}
}
