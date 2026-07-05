package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that {@link LocalOrgIdentitySync} is the active
 * {@link OrgIdentitySync} when {@code auth.enabled} is false (the default).
 */
@TestPropertySource(properties = "auth.enabled=false")
public class OrgIdentitySyncNoAuthSelectionTest extends BaseTest {

    @Autowired
    private OrgIdentitySync orgIdentity;

    @Test
    void localImplIsSelectedWhenAuthDisabled() {
        assertThat(orgIdentity).isInstanceOf(LocalOrgIdentitySync.class);
    }
}
