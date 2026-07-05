package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Verifies that {@link SingleTenantUserInfoProvider} is the active {@link UserInfoProvider}
 * when {@code auth.enabled} is false (the default in the test profile).
 */
@TestPropertySource(properties = "auth.enabled=false")
public class UserInfoProviderNoAuthSelectionTest extends BaseTest {

    @Autowired
    private UserInfoProvider userInfoProvider;

    @Test
    void singleTenantUserInfoProviderIsSelectedWhenAuthDisabled() {
        assertThat(userInfoProvider).isInstanceOf(SingleTenantUserInfoProvider.class);
    }
}
