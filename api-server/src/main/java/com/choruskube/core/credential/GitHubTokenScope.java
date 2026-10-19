package com.choruskube.core.credential;

import com.choruskube.core.exception.GitHubTokenScopeException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A GitHub token's intended scope: the repositories it should cover, and the permissions it should
 * carry. {@link #narrowTo} applies the narrowing rule that both the API server and the Worker's
 * {@code worker/credential/scope.go} implement identically — a repository name GitHub resolves
 * against the minting installation's own account, so the rule can only run once that account is
 * known.
 */
public record GitHubTokenScope(List<String> repositories, Map<String, String> permissions) {

    private static final Logger log = LoggerFactory.getLogger(GitHubTokenScope.class);

    /** read=1 < write=2 < admin=3 — the rank a requested permission is capped against a grant by. */
    private static final Map<String, Integer> LEVEL_RANK = Map.of("read", 1, "write", 2, "admin", 3);

    private static final Map<Integer, String> RANK_LEVEL = Map.of(1, "read", 2, "write", 3, "admin");

    /** The permissions every run's GitHub work uses. The Worker never hardcodes this; it fetches it from the scope route. */
    public static final Map<String, String> RUN_PERMISSIONS = sortedUnmodifiable(Map.of(
            "actions", "read",
            "checks", "read",
            "contents", "write",
            "issues", "write",
            "metadata", "read",
            "pull_requests", "write",
            "statuses", "read",
            "workflows", "write"));

    public GitHubTokenScope(List<String> repositories, Map<String, String> permissions) {
        this.repositories = List.copyOf(repositories);
        this.permissions = sortedUnmodifiable(permissions);
    }

    /**
     * A scope of exactly the given {@code owner/name} repositories, de-duplicated case-insensitively
     * (keeping whichever spelling sorts first in byte order) and sorted, carrying {@link
     * #RUN_PERMISSIONS}.
     */
    public static GitHubTokenScope of(Collection<String> ownerRepos) {
        TreeMap<String, String> byLowerCase = new TreeMap<>();
        for (String repo : ownerRepos) {
            if (repo == null || repo.isBlank()) {
                continue;
            }
            String key = repo.toLowerCase(Locale.ROOT);
            String existing = byLowerCase.get(key);
            if (existing == null || repo.compareTo(existing) < 0) {
                byLowerCase.put(key, repo);
            }
        }
        List<String> names = new ArrayList<>(byLowerCase.values());
        names.sort(String::compareTo);
        return new GitHubTokenScope(names, RUN_PERMISSIONS);
    }

    /**
     * Narrows this scope to what a mint may actually request, now that the installation's account
     * and grants are known:
     *
     * <ol>
     *   <li>An empty scope is refused outright;
     *   <li>repositories under another owner are dropped (logged by name, once, at WARN);
     *   <li>the remaining repositories are reduced to their bare name, de-duplicated
     *       case-insensitively (keeping the spelling that sorts first) and sorted;
     *   <li>each permission is requested at the lower of its desired and granted level; a
     *       permission the installation does not grant is omitted.
     * </ol>
     *
     * An empty result at either step throws {@link GitHubTokenScopeException} naming the dropped
     * repositories and the account.
     */
    public Request narrowTo(String accountLogin, Map<String, String> granted) {
        if (repositories.isEmpty()) {
            throw new GitHubTokenScopeException("No repository in this run's scope");
        }

        TreeMap<String, String> namesByLowerCase = new TreeMap<>();
        List<String> droppedForeignOwner = new ArrayList<>();
        for (String repo : repositories) {
            String[] parts = repo.split("/", -1);
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
                // Never produced by RepoNameUtil#githubOwnerRepo; ignored rather than treated as a
                // foreign-owner drop, since there is no owner here to name in the error.
                continue;
            }
            String owner = parts[0];
            String name = parts[1];
            if (!owner.equalsIgnoreCase(accountLogin)) {
                droppedForeignOwner.add(repo);
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            String existing = namesByLowerCase.get(key);
            if (existing == null || name.compareTo(existing) < 0) {
                namesByLowerCase.put(key, name);
            }
        }

        if (!droppedForeignOwner.isEmpty()) {
            log.warn(
                    "Dropped repositories not owned by GitHub App installation account '{}': {}",
                    accountLogin,
                    droppedForeignOwner);
        }
        if (namesByLowerCase.isEmpty()) {
            throw new GitHubTokenScopeException("No repository this installation can reach: dropped "
                    + droppedForeignOwner + " (installation account '" + accountLogin + "')");
        }

        List<String> names = new ArrayList<>(namesByLowerCase.values());
        names.sort(String::compareTo);

        TreeMap<String, String> cappedPermissions = new TreeMap<>();
        for (Map.Entry<String, String> entry : permissions.entrySet()) {
            Integer desiredRank = rankOf(entry.getValue());
            Integer grantedRank = rankOf(granted.get(entry.getKey()));
            if (desiredRank == null || grantedRank == null) {
                continue;
            }
            cappedPermissions.put(entry.getKey(), RANK_LEVEL.get(Math.min(desiredRank, grantedRank)));
        }
        if (cappedPermissions.isEmpty()) {
            throw new GitHubTokenScopeException(
                    "No permission this installation grants for account '" + accountLogin + "'");
        }

        return new Request(names, cappedPermissions);
    }

    /** What a mint may actually send: bare repository names and the capped permissions, both sorted. */
    public record Request(List<String> repositoryNames, Map<String, String> permissions) {
        public Request(List<String> repositoryNames, Map<String, String> permissions) {
            this.repositoryNames = List.copyOf(repositoryNames);
            this.permissions = sortedUnmodifiable(permissions);
        }
    }

    // Map.of(...) throws NullPointerException on a null-key lookup, which granted.get(permission)
    // produces for any permission the installation doesn't grant — the common case.
    private static Integer rankOf(String level) {
        return level == null ? null : LEVEL_RANK.get(level);
    }

    // Collections.unmodifiableMap, not Map.copyOf, because Map.copyOf's returned implementation
    // does not preserve the backing TreeMap's sorted iteration order — and that order is what
    // keeps a mint body's "permissions" keys sorted (the shared mint-body contract).
    private static Map<String, String> sortedUnmodifiable(Map<String, String> source) {
        return Collections.unmodifiableMap(new TreeMap<>(source));
    }
}
