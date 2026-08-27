package com.choruskube.core.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Strategy for synchronising organization + user identity state with an external
 * identity provider. Selected at startup by Spring
 * {@code @ConditionalOnProperty} keyed on {@code auth.enabled}:
 * <ul>
 *   <li>the auth-enabled identity-sync impl when {@code auth.enabled=true}</li>
 *   <li>{@link LocalOrgIdentitySync} when {@code auth.enabled} is unset or false (default)</li>
 * </ul>
 *
 * <p>The {@link LocalOrgIdentitySync} impl returns conservative defaults (empty
 * Optionals, empty lists, {@code false}) for lookups and treats mutations as
 * WARN-logged no-ops. This keeps callers null-safe in OSS / single-tenant mode
 * where there is no external identity store.
 *
 * <p>Type aliases {@link IdentityOrgRef} and {@link IdentityUserRef} mirror the structure of
 * the identity-provider admin client's org-ref / user-ref so callers can keep
 * their accessor calls ({@code .id()}, {@code .alias()}, etc.) stable while the
 * field type changes.
 */
public interface OrgIdentitySync {

    /** Type alias for the identity-provider's org-ref so consumers can import via the interface. */
    final class IdentityOrgRef {
        private final UUID id;
        private final String alias;
        private final String displayName;

        public IdentityOrgRef(UUID id, String alias, String displayName) {
            this.id = id;
            this.alias = alias;
            this.displayName = displayName;
        }

        public UUID id() {
            return id;
        }

        public String alias() {
            return alias;
        }

        public String displayName() {
            return displayName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IdentityOrgRef other)) return false;
            return Objects.equals(id, other.id)
                    && Objects.equals(alias, other.alias)
                    && Objects.equals(displayName, other.displayName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, alias, displayName);
        }

        @Override
        public String toString() {
            return "IdentityOrgRef[id=" + id + ", alias=" + alias + ", displayName=" + displayName + "]";
        }
    }

    /** Type alias for the identity-provider's user-ref so consumers can import via the interface. */
    final class IdentityUserRef {
        private final UUID id;
        private final String email;
        private final String firstName;
        private final String lastName;
        private final String username;

        public IdentityUserRef(UUID id, String email, String firstName, String lastName, String username) {
            this.id = id;
            this.email = email;
            this.firstName = firstName;
            this.lastName = lastName;
            this.username = username;
        }

        public UUID id() {
            return id;
        }

        public String email() {
            return email;
        }

        public String firstName() {
            return firstName;
        }

        public String lastName() {
            return lastName;
        }

        public String username() {
            return username;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof IdentityUserRef other)) return false;
            return Objects.equals(id, other.id)
                    && Objects.equals(email, other.email)
                    && Objects.equals(firstName, other.firstName)
                    && Objects.equals(lastName, other.lastName)
                    && Objects.equals(username, other.username);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, email, firstName, lastName, username);
        }

        @Override
        public String toString() {
            return "IdentityUserRef[id=" + id + ", email=" + email + ", firstName=" + firstName + ", lastName="
                    + lastName + ", username=" + username + "]";
        }
    }

    Optional<UUID> findOrgByAlias(String alias);

    long countOrgMembers(UUID orgId);

    List<IdentityUserRef> listOrgMembers(UUID orgId, int first, int max);

    /**
     * Per-org role of every member holding one, as a {@code userId -> role} map (keys are the
     * identity provider's user id in string form; values are {@code viewer}/{@code operator}/
     * {@code org-admin}). Members with no org role are absent. A member somehow in more than one
     * role resolves to the single highest-priority one. Empty in single-tenant / OSS mode.
     */
    Map<String, String> listOrgRoleMembers(UUID orgId);

    boolean isOrgMember(UUID orgId, String email);

    Optional<UUID> findUserByEmail(String email);

    List<IdentityOrgRef> listUserOrganizations(UUID userId);

    UUID createOrganization(String alias, String name, String description);

    void deleteOrganization(UUID orgId);

    UUID createUser(String email, String firstName, String lastName);

    void addOrgMember(UUID orgId, UUID userId);

    void removeOrgMember(UUID orgId, UUID userId);

    void assignGlobalRole(UUID userId, String roleName);

    void removeGlobalRole(UUID userId, String roleName);

    void assignOrgRole(UUID orgId, UUID userId, String role);

    void removeOrgRole(UUID orgId, UUID userId, String role);
}
