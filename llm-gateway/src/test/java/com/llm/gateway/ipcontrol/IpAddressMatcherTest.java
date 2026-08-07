package com.llm.gateway.ipcontrol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IpAddressMatcherTest {

    @Test
    void matchesIpv4AndIpv6Cidrs() {
        assertThat(IpAddressMatcher.parse("10.0.0.0/8").matches("10.20.30.40")).isTrue();
        assertThat(IpAddressMatcher.parse("10.0.0.0/8").matches("11.20.30.40")).isFalse();
        assertThat(IpAddressMatcher.parse("2001:db8::/32").matches("2001:db8::1234"))
                .isTrue();
        assertThat(IpAddressMatcher.parse("2001:db8::/32").matches("2001:db9::1"))
                .isFalse();
    }

    @Test
    void normalizesEquivalentIpv6Forms() {
        String normalized = IpAddressMatcher.normalizeAddress("[0:0:0:0:0:0:0:1]");

        assertThat(normalized).isEqualTo("0:0:0:0:0:0:0:1");
        assertThat(IpAddressMatcher.parse("::1").matches(normalized)).isTrue();
    }

    @Test
    void rejectsHostnamesAndInvalidPrefixes() {
        assertThatThrownBy(() -> IpAddressMatcher.parse("example.com"))
                .isInstanceOf(IpControlValidationException.class);
        assertThatThrownBy(() -> IpAddressMatcher.parse("127.1"))
                .isInstanceOf(IpControlValidationException.class);
        assertThatThrownBy(() -> IpAddressMatcher.parse("256.0.0.1"))
                .isInstanceOf(IpControlValidationException.class);
        assertThatThrownBy(() -> IpAddressMatcher.parse("10.0.0.0/33"))
                .isInstanceOf(IpControlValidationException.class);
    }
}
