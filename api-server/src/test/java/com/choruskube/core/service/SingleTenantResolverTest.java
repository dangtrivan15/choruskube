package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.SingleTenant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Behavior test for {@link SingleTenantResolver}: verifies the OSS resolver returns
 * the system organization id and a stable implicit user id across consecutive calls.
 */
@TestPropertySource(properties = "auth.enabled=false")
public class SingleTenantResolverTest extends BaseTest {

    @Autowired
    private TenantResolver tenantResolver;

    @Test
    void resolveReturnsSystemOrgAndStableUser() {
        TenantResolver.ResolvedTenant first = tenantResolver.resolve(null);
        assertThat(first.organizationId()).isEqualTo(SingleTenant.ID);
        assertThat(first.userId()).isNotNull();

        TenantResolver.ResolvedTenant second = tenantResolver.resolve(null);
        assertThat(second.userId()).isEqualTo(first.userId());
    }
}
