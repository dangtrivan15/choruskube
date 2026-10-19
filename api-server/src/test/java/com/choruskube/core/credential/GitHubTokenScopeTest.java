package com.choruskube.core.credential;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.exception.GitHubTokenScopeException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The narrowing rule's case table — kept identical to {@code worker/credential/scope_test.go}'s,
 * since both languages must drop and cap exactly the same way.
 */
class GitHubTokenScopeTest {

    private static GitHubTokenScope scopeOf(String... ownerRepos) {
        return new GitHubTokenScope(List.of(ownerRepos), GitHubTokenScope.RUN_PERMISSIONS);
    }

    // -----------------------------------------------------------------------------------
    // Repository narrowing
    // -----------------------------------------------------------------------------------

    @Test
    void ownerMatch_mixedCase_keepsBothBareNames() {
        var request = scopeOf("Acme/API", "acme/web").narrowTo("acme", GitHubTokenScope.RUN_PERMISSIONS);

        assertThat(request.repositoryNames()).containsExactly("API", "web");
    }

    @Test
    void foreignOwner_isDropped() {
        var request = scopeOf("acme/api", "other/tools").narrowTo("acme", GitHubTokenScope.RUN_PERMISSIONS);

        assertThat(request.repositoryNames()).containsExactly("api");
    }

    @Test
    void allForeign_refuses() {
        assertThatThrownBy(() -> scopeOf("other/tools").narrowTo("acme", GitHubTokenScope.RUN_PERMISSIONS))
                .isInstanceOf(GitHubTokenScopeException.class)
                .hasMessageContaining("other/tools")
                .hasMessageContaining("acme");
    }

    @Test
    void duplicates_sameCase_deduplicated() {
        var request = scopeOf("acme/api", "ACME/api").narrowTo("acme", GitHubTokenScope.RUN_PERMISSIONS);

        assertThat(request.repositoryNames()).containsExactly("api");
    }

    @Test
    void duplicateNamesDifferingInCase_keepsTheSpellingThatSortsFirst() {
        var request = scopeOf("acme/api", "acme/API").narrowTo("acme", GitHubTokenScope.RUN_PERMISSIONS);

        assertThat(request.repositoryNames()).containsExactly("API");
    }

    @Test
    void empty_refuses() {
        assertThatThrownBy(() -> scopeOf().narrowTo("acme", GitHubTokenScope.RUN_PERMISSIONS))
                .isInstanceOf(GitHubTokenScopeException.class);
    }

    @Test
    void malformedEntry_isIgnored() {
        var request = scopeOf("acme", "acme/a/b", "acme/api").narrowTo("acme", GitHubTokenScope.RUN_PERMISSIONS);

        assertThat(request.repositoryNames()).containsExactly("api");
    }

    // -----------------------------------------------------------------------------------
    // Permission narrowing
    // -----------------------------------------------------------------------------------

    @Test
    void grantedRead_requestsRead_belowDesiredWrite() {
        var request = scopeOf("acme/api").narrowTo("acme", Map.of("contents", "read", "metadata", "read"));

        assertThat(request.permissions()).containsEntry("contents", "read");
    }

    @Test
    void grantedAdmin_requestsTheLowerDesiredWrite() {
        var request = scopeOf("acme/api").narrowTo("acme", Map.of("contents", "admin", "metadata", "read"));

        assertThat(request.permissions()).containsEntry("contents", "write");
    }

    @Test
    void notGranted_isOmitted() {
        var request = scopeOf("acme/api")
                .narrowTo(
                        "acme",
                        Map.of(
                                "contents", "write",
                                "metadata", "read"));

        assertThat(request.permissions()).doesNotContainKey("workflows");
    }

    @Test
    void grantedAdministration_isNeverRequested() {
        var request = scopeOf("acme/api")
                .narrowTo(
                        "acme",
                        Map.of(
                                "contents", "write",
                                "metadata", "read",
                                "administration", "write"));

        assertThat(request.permissions()).doesNotContainKey("administration");
    }

    @Test
    void emptyGrantedPermissions_refuses() {
        assertThatThrownBy(() -> scopeOf("acme/api").narrowTo("acme", Map.of()))
                .isInstanceOf(GitHubTokenScopeException.class);
    }

    @Test
    void grantedOnlyUnknownLevels_refuses() {
        assertThatThrownBy(() -> scopeOf("acme/api").narrowTo("acme", Map.of("contents", "bogus")))
                .isInstanceOf(GitHubTokenScopeException.class);
    }

    // -----------------------------------------------------------------------------------
    // of()
    // -----------------------------------------------------------------------------------

    @Test
    void of_deduplicatesCaseInsensitivelyAndSorts() {
        GitHubTokenScope scope = GitHubTokenScope.of(List.of("acme/web", "acme/API", "acme/api"));

        assertThat(scope.repositories()).containsExactly("acme/API", "acme/web");
        assertThat(scope.permissions()).isEqualTo(GitHubTokenScope.RUN_PERMISSIONS);
    }

    @Test
    void of_blankEntriesIgnored() {
        GitHubTokenScope scope = GitHubTokenScope.of(List.of("", "  ", "acme/api"));

        assertThat(scope.repositories()).containsExactly("acme/api");
    }
}
