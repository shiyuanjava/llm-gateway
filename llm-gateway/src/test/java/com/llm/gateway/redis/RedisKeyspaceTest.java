package com.llm.gateway.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisKeyspaceTest {

    @Test
    void hashesSensitiveScopeAndKeepsSameSlotTag() {
        GatewayRedisProperties properties = new GatewayRedisProperties();
        properties.setNamespace("llmgw");
        properties.setEnvironment("prod-cn");
        RedisKeyspace keyspace = new RedisKeyspace(properties);

        String state = keyspace.key("quota", "tenant-secret", "state");
        String reservations = keyspace.key("quota", "tenant-secret", "reservations");

        assertThat(state).startsWith("llmgw:v2:prod-cn:quota:{");
        assertThat(state).doesNotContain("tenant-secret");
        assertThat(keyspace.hashTag(state)).isEqualTo(keyspace.hashTag(reservations));
    }

    @Test
    void rejectsUnsafeSegments() {
        GatewayRedisProperties properties = new GatewayRedisProperties();
        RedisKeyspace keyspace = new RedisKeyspace(properties);

        assertThatThrownBy(() -> keyspace.key("quota:*", "tenant", "state"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
