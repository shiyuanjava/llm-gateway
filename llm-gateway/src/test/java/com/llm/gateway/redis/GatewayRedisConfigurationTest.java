package com.llm.gateway.redis;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRedisConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(GatewayRedisConfiguration.class)
            .withPropertyValues(
                    "gateway.redis.control.nodes[0]=127.0.0.1:6379", "gateway.redis.cache.nodes[0]=127.0.0.1:6380");

    @Test
    void createsIndependentControlAndCacheConnectionsWithoutConnecting() {
        runner.run(context -> {
            assertThat(context).hasBean("controlRedisTemplate");
            assertThat(context).hasBean("cacheRedisTemplate");
            assertThat(context.getBean("controlRedisTemplate", StringRedisTemplate.class))
                    .isNotSameAs(context.getBean("cacheRedisTemplate", StringRedisTemplate.class));
            LettuceConnectionFactory control =
                    context.getBean("controlRedisConnectionFactory", LettuceConnectionFactory.class);
            LettuceConnectionFactory cache =
                    context.getBean("cacheRedisConnectionFactory", LettuceConnectionFactory.class);
            assertThat(control.getPort()).isEqualTo(6379);
            assertThat(cache.getPort()).isEqualTo(6380);
        });
    }
}
