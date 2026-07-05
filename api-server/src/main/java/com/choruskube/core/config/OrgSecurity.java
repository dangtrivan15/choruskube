package com.choruskube.core.config;

public interface OrgSecurity {

    /** Any authenticated user (viewer, operator, admin) can read. */
    boolean canRead();

    /** Operator or org-admin can operate (create, cancel, pause, resume, signal, start proposals). */
    boolean canOperate();

    /**
     * Only org-admin can perform full CRUD (update, delete on definitions/repos/templates).
     *
     * <p>Note: the role is named {@code org-admin}, not {@code admin}. The identity provider
     * treats the literal name {@code admin} as a reserved role and restricts assignment to platform
     * admins only — so the role name had to change to keep the invitation bootstrap path
     * (assign/remove the global role at invite-time) working from a non-master service account.
     */
    boolean canAdmin();

    /**
     * Platform admin: a user with the org-admin role whose <em>identity</em> org is the system
     * org. Reads the caller's real/identity organization (not the active impersonated org) so that
     * impersonation ("Manage as") does not strip the caller of their platform-admin rights — the
     * active org may have been swapped, but identity-org-membership is what determines this check.
     */
    boolean isPlatformAdmin();
}
