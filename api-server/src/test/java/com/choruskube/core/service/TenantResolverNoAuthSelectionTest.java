package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.SharedContextTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies that {@link SingleTenantResolver} is the active {@link TenantResolver}
 * when {@code auth.enabled} is false (the default in the test profile).
 */
public class TenantResolverNoAuthSelectionTest extends SharedContextTest {

    @Autowired
    private TenantResolver tenantResolver;

    @Test
    void singleTenantResolverIsSelectedWhenAuthDisabled() {
        assertThat(tenantResolver).isInstanceOf(SingleTenantResolver.class);
    }
}
