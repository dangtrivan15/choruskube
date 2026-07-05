package com.choruskube.core.service;

import com.choruskube.core.config.SingleTenant;
import com.choruskube.core.dto.OrgRef;
import com.choruskube.core.dto.UserInfoResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * OSS {@code /me}: a single implicit user who owns the single system org. Active when
 * {@code auth.enabled} is absent/false. The {@code authentication} arg is ignored
 * (OSS issues no JWT).
 *
 * <p>Fully synthetic — the response is built from {@link SingleTenant} constants with no
 * database I/O. Core carries no identity: {@code /me} never reads or materializes a user
 * row. The synthetic active org is required
 * (a {@code null} active org would make the web UI render the multi-org workspace picker,
 * which is wrong for single-tenant). Uses the same system org/user identity as
 * {@link SingleTenantResolver}.
 */
@Service
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class SingleTenantUserInfoProvider implements UserInfoProvider {

    /**
     * Stable synthetic id for the implicit OSS user. A fixed compile-time constant (no DB
     * row backs it), chosen distinct from {@link SingleTenant#ID} so the user and org ids never
     * collide.
     */
    public static final UUID SINGLE_TENANT_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Override
    public UserInfoResponse getMe(Authentication authentication) {
        OrgRef defaultOrg = new OrgRef(SingleTenant.ID, SingleTenant.SLUG, "System");
        // OSS single-tenant: the implicit user fully controls their one org (role=org-admin),
        // onboarding is implicitly complete, and there is no cross-org platform-admin concept
        // -> platformAdmin=false.
        return new UserInfoResponse(SINGLE_TENANT_USER_ID, defaultOrg, List.of(defaultOrg), false, true, "org-admin");
    }
}
