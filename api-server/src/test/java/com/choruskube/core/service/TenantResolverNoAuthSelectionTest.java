package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that {@link SingleTenantResolver} is the active {@link TenantResolver}
 * when {@code auth.enabled} is false (the default in the test profile).
 */
@TestPropertySource(properties = "auth.enabled=false")
public class TenantResolverNoAuthSelectionTest extends BaseTest {

    @Autowired
    private TenantResolver tenantResolver;

    @Test
    void singleTenantResolverIsSelectedWhenAuthDisabled() {
        assertThat(tenantResolver).isInstanceOf(SingleTenantResolver.class);
    }
}
