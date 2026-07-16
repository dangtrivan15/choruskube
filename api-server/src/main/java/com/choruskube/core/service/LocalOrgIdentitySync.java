package com.choruskube.core.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@link OrgIdentitySync} for OSS / single-tenant mode. Returns conservative
 * defaults (empty Optional, empty list, {@code false}) for lookups and
 * WARN-logs every mutation before returning a placeholder ID or no-oping.
 *
 * <p>Active when {@code auth.enabled} is absent or {@code false} (the default).
 * Used in the OSS Docker stack where there is no external identity provider deployment — the
 * multi-tenant Org admin endpoints that would call these methods are not
 * mounted in OSS UI routes, but if they are reached via direct API call we
 * must not crash the JVM.
 */
@Component
@ConditionalOnProperty(name = "auth.enabled", havingValue = "false", matchIfMissing = true)
public class LocalOrgIdentitySync implements OrgIdentitySync {

    private static final Logger log = LoggerFactory.getLogger(LocalOrgIdentitySync.class);

    @Override
    public Optional<UUID> findOrgByAlias(String alias) {
        return Optional.empty();
    }

    @Override
    public long countOrgMembers(UUID orgId) {
        return 0L;
    }

    @Override
    public List<IdentityUserRef> listOrgMembers(UUID orgId, int first, int max) {
        return List.of();
    }

    @Override
    public boolean isOrgMember(UUID orgId, String email) {
        return false;
    }

    @Override
    public Optional<UUID> findUserByEmail(String email) {
        return Optional.empty();
    }

    @Override
    public List<IdentityOrgRef> listUserOrganizations(UUID userId) {
        return List.of();
    }

    @Override
    public UUID createOrganization(String alias, String name, String description) {
        log.warn(
                "createOrganization('{}') called in OSS mode — returning placeholder id; no identity-provider org created",
                alias);
        return UUID.randomUUID();
    }

    @Override
    public void deleteOrganization(UUID orgId) {
        log.warn("deleteOrganization({}) called in OSS mode — no-op", orgId);
    }

    @Override
    public UUID createUser(String email, String firstName, String lastName) {
        log.warn(
                "createUser('{}') called in OSS mode — returning placeholder id; no identity-provider user created",
                email);
        return UUID.randomUUID();
    }

    @Override
    public void addOrgMember(UUID orgId, UUID userId) {
        log.warn("addOrgMember({}, {}) called in OSS mode — no-op", orgId, userId);
    }

    @Override
    public void removeOrgMember(UUID orgId, UUID userId) {
        log.warn("removeOrgMember({}, {}) called in OSS mode — no-op", orgId, userId);
    }

    @Override
    public void assignGlobalRole(UUID userId, String roleName) {
        log.warn("assignGlobalRole({}, '{}') called in OSS mode — no-op", userId, roleName);
    }

    @Override
    public void removeGlobalRole(UUID userId, String roleName) {
        log.warn("removeGlobalRole({}, '{}') called in OSS mode — no-op", userId, roleName);
    }

    @Override
    public void assignOrgRole(UUID orgId, UUID userId, String role) {
        log.warn("assignOrgRole({}, {}, '{}') called in OSS mode — no-op", orgId, userId, role);
    }

    @Override
    public void removeOrgRole(UUID orgId, UUID userId, String role) {
        log.warn("removeOrgRole({}, {}, '{}') called in OSS mode — no-op", orgId, userId, role);
    }
}
