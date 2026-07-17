package com.choruskube.core.util;

/**
 * Derives a display name for a git repository from its URL.
 *
 * <p>The derived name is used in UI chips, response DTOs, and graph snapshots.
 * This utility is the single source of truth for the derivation so that all
 * consumers — {@code GraphSnapshotBuilder}, {@code DefaultEpicService},
 * etc. — stay consistent.
 */
public final class RepoNameUtil {

    private RepoNameUtil() {}

    /**
     * Derives the display name from a git URL by stripping a trailing {@code .git}
     * and returning the last path segment. Handles both HTTPS and SSH URLs.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code https://github.com/org/backend-api.git} → {@code backend-api}</li>
     *   <li>{@code git@github.com:org/web-ui.git} → {@code web-ui}</li>
     *   <li>{@code https://github.com/org/repo/} → {@code repo}</li>
     * </ul>
     *
     * @param url the git URL; may be null or blank
     * @return the derived name, or an empty string if the URL is null or blank
     */
    public static String deriveRepoName(String url) {
        if (url == null || url.isBlank()) return "";
        // Drop any query string/fragment — hosts sometimes hand back ".../repo.git?ref=main".
        int q = url.indexOf('?');
        String cleaned = q >= 0 ? url.substring(0, q) : url;
        int f = cleaned.indexOf('#');
        if (f >= 0) cleaned = cleaned.substring(0, f);
        // Drop trailing slashes so "https://x/y/repo/" still derives "repo".
        String trimmed = cleaned.replaceAll("/+$", "");
        String path = trimmed.replaceAll("\\.git$", "");
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash >= 0) return path.substring(lastSlash + 1);
        // SSH URLs look like "git@host:org/repo.git" — fall back to splitting on ':'.
        int lastColon = path.lastIndexOf(':');
        if (lastColon >= 0) return path.substring(lastColon + 1);
        return path;
    }

    /**
     * Derives a collision-resistant {@code owner/repo} identifier from a git URL — used as the
     * persisted {@code SoftwareProject.name} for {@code GitRepo} rows. The two-segment form keeps
     * the {@code (organization_id, name)} unique constraint robust against URLs that share a last
     * segment (e.g. {@code github.com/foo/repo} and {@code github.com/bar/repo}). Falls back to
     * {@link #deriveRepoName} when only one segment is available.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code https://github.com/org/backend-api.git} → {@code org/backend-api}</li>
     *   <li>{@code git@github.com:org/web-ui.git} → {@code org/web-ui}</li>
     *   <li>{@code https://gitlab.com/group/subgroup/svc} → {@code subgroup/svc}</li>
     *   <li>{@code mylocalrepo.git} → {@code mylocalrepo}</li>
     * </ul>
     */
    public static String deriveOwnerRepoName(String url) {
        String last = deriveRepoName(url);
        if (last.isEmpty()) return "";
        // Re-extract the owner segment from the cleaned path. Reuse the cleaning logic
        // by trimming the URL the same way deriveRepoName does, then walking back one
        // segment from the end. Splitting on both '/' and ':' covers HTTPS and SSH URLs.
        int q = url.indexOf('?');
        String cleaned = q >= 0 ? url.substring(0, q) : url;
        int f = cleaned.indexOf('#');
        if (f >= 0) cleaned = cleaned.substring(0, f);
        String path = cleaned.replaceAll("/+$", "").replaceAll("\\.git$", "");
        // Find the boundary between {owner-segment, last-segment}. For HTTPS URLs that's the
        // second-to-last '/'; for SSH URLs it can be the host:owner colon.
        int lastSep = Math.max(path.lastIndexOf('/'), path.lastIndexOf(':'));
        if (lastSep <= 0) return last; // single segment — nothing to prepend
        String head = path.substring(0, lastSep);
        int prevSep = Math.max(head.lastIndexOf('/'), head.lastIndexOf(':'));
        String owner = prevSep >= 0 ? head.substring(prevSep + 1) : head;
        return owner.isEmpty() ? last : owner + "/" + last;
    }
}
