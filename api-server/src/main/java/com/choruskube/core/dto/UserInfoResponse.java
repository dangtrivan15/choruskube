package com.choruskube.core.dto;

import java.util.List;
import java.util.UUID;

/**
 * Shape of the {@code /api/v1/me} response. Under multi-org, a user may have no active org
 * selected (empty {@code organization} JWT claim) — in that case {@code activeOrg} is
 * {@code null} and the web UI renders the workspace picker from {@code memberships}.
 *
 * <p>{@code role} is a global role ({@code admin}/{@code operator}/{@code viewer});
 * ChorusKube does not use per-org roles, so this is the user's role across every org they
 * belong to.
 */
public record UserInfoResponse(
        UUID userId,
        OrgRef activeOrg,
        List<OrgRef> memberships,
        boolean platformAdmin,
        boolean onboardingCompleted,
        String role) {}
