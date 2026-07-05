package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.config.SingleTenant;
import com.choruskube.core.dto.UserInfoResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * Behavior test for {@link SingleTenantUserInfoProvider}: the OSS {@code /me} is fully
 * synthetic — it returns a stable implicit user owning the single system org, derived
 * entirely from {@link SingleTenant} constants with no database I/O. Critically, calling
 * {@code getMe} must not provision any identity row; core carries no identity tables.
 * The {@code authentication} argument is ignored (OSS issues no JWT).
 */
@TestPropertySource(properties = "auth.enabled=false")
public class SingleTenantUserInfoProviderTest extends BaseTest {

    @Autowired
    private UserInfoProvider userInfoProvider;

    @Test
    void getMeIsSyntheticAndCreatesNoAppUser() {
        UserInfoResponse me = userInfoProvider.getMe(null);

        // Synthetic, constant identity — not a DB-materialized user.
        assertThat(me.userId()).isEqualTo(SingleTenantUserInfoProvider.SINGLE_TENANT_USER_ID);
        assertThat(me.role()).isEqualTo("org-admin");
        assertThat(me.platformAdmin()).isFalse();
        assertThat(me.onboardingCompleted()).isTrue();

        // Synthetic active org from SingleTenant constants — required so the web UI does not
        // render the multi-org picker (a null activeOrg would wrongly trigger it).
        assertThat(me.activeOrg()).isNotNull();
        assertThat(me.activeOrg().id()).isEqualTo(SingleTenant.ID);
        assertThat(me.activeOrg().slug()).isEqualTo(SingleTenant.SLUG);

        assertThat(me.memberships()).hasSize(1);
        assertThat(me.memberships().get(0).id()).isEqualTo(SingleTenant.ID);
        assertThat(me.memberships().get(0).slug()).isEqualTo(SingleTenant.SLUG);

        // /me is purely synthetic — calling it twice returns identical stable results.
        UserInfoResponse second = userInfoProvider.getMe(null);
        assertThat(second.userId()).isEqualTo(me.userId());
        assertThat(second.activeOrg().id()).isEqualTo(me.activeOrg().id());
    }
}
