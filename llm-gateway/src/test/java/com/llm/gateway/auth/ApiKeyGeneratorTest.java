package com.llm.gateway.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ApiKeyGeneratorTest {

    @Test
    void generatesPrefixedRandomKey() {
        String key = ApiKeyGenerator.generate();
        assertThat(key).startsWith("sk-gw-").hasSize(6 + 32);
        assertThat(ApiKeyGenerator.generate()).isNotEqualTo(key);
    }

    @Test
    void sha256IsDeterministicHex() {
        String h1 = ApiKeyGenerator.sha256("sk-demo-tenant-a");
        assertThat(h1).hasSize(64).matches("[0-9a-f]+");
        assertThat(ApiKeyGenerator.sha256("sk-demo-tenant-a")).isEqualTo(h1);
        // 与 MySQL SHA2(x,256) 输出一致性:已知值断言
        assertThat(ApiKeyGenerator.sha256("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void prefixTakesFirstTwelveChars() {
        assertThat(ApiKeyGenerator.prefixOf("sk-gw-0123456789abcdef")).isEqualTo("sk-gw-012345");
        assertThat(ApiKeyGenerator.prefixOf("short")).isEqualTo("short");
    }
}
