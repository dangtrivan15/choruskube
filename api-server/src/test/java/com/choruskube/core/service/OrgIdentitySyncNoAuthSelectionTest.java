package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.SharedContextTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies that {@link LocalOrgIdentitySync} is the active
 * {@link OrgIdentitySync} when {@code auth.enabled} is false (the default).
 */
public class OrgIdentitySyncNoAuthSelectionTest extends SharedContextTest {

    @Autowired
    private OrgIdentitySync orgIdentity;

    @Test
    void localImplIsSelectedWhenAuthDisabled() {
        assertThat(orgIdentity).isInstanceOf(LocalOrgIdentitySync.class);
    }
}
