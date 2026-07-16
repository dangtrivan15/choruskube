package com.choruskube.core.dto;

import java.util.List;
import java.util.UUID;

/**
 * Shape of the {@code /api/v1/me} response. Under multi-org, a user may have no active org
 * selected (empty {@code organization} JWT claim) — in that case {@code activeOrg} is
 * {@code null} and the web UI renders the workspace picker from {@code memberships}.
 *
 * <p>{@code role} is {@code admin}/{@code operator}/{@code viewer}, populated per {@code
 * UserInfoProvider} implementation. In OSS / single-tenant mode it is the user's one global role,
 * identical across the (single, synthetic) org they belong to. An auth-enabled implementation may
 * instead populate it with the caller's role scoped to whichever organization is currently active,
 * which can differ from their role in another organization they also belong to.
 */
public record UserInfoResponse(
        UUID userId,
        OrgRef activeOrg,
        List<OrgRef> memberships,
        boolean platformAdmin,
        boolean onboardingCompleted,
        String role) {}
