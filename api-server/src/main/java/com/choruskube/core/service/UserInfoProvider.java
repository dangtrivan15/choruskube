package com.choruskube.core.service;

import com.choruskube.core.dto.UserInfoResponse;
import org.springframework.security.core.Authentication;

/**
 * Builds the {@code /api/v1/me} payload. Strategy selected by {@code auth.enabled}:
 * the auth-enabled impl reads JWT claims + identity-provider memberships; the single-tenant impl returns the
 * implicit system user. {@code UserController} (core) holds no JWT code.
 */
public interface UserInfoProvider {
    UserInfoResponse getMe(Authentication authentication);
}
