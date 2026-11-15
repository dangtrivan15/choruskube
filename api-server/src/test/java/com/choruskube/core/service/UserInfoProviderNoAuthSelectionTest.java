package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.SharedContextTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies that {@link SingleTenantUserInfoProvider} is the active {@link UserInfoProvider}
 * when {@code auth.enabled} is false (the default in the test profile).
 */
public class UserInfoProviderNoAuthSelectionTest extends SharedContextTest {

    @Autowired
    private UserInfoProvider userInfoProvider;

    @Test
    void singleTenantUserInfoProviderIsSelectedWhenAuthDisabled() {
        assertThat(userInfoProvider).isInstanceOf(SingleTenantUserInfoProvider.class);
    }
}
