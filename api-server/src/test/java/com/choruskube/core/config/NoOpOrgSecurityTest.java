package com.choruskube.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NoOpOrgSecurityTest {

    private final NoOpOrgSecurity noOp = new NoOpOrgSecurity();

    @Test
    void canRead_returnsTrue() {
        assertThat(noOp.canRead()).isTrue();
    }

    @Test
    void canOperate_returnsTrue() {
        assertThat(noOp.canOperate()).isTrue();
    }

    @Test
    void canAdmin_returnsTrue() {
        assertThat(noOp.canAdmin()).isTrue();
    }

    @Test
    void isPlatformAdmin_returnsTrue() {
        assertThat(noOp.isPlatformAdmin()).isTrue();
    }
}
