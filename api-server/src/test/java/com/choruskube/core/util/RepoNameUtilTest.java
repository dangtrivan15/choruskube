package com.choruskube.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RepoNameUtilTest {

    @Test
    void deriveRepoName_stripsDotGit() {
        assertThat(RepoNameUtil.deriveRepoName("https://github.com/org/backend-api.git"))
                .isEqualTo("backend-api");
    }

    @Test
    void deriveRepoName_extractsLastPathSegment() {
        assertThat(RepoNameUtil.deriveRepoName("https://gitlab.com/group/subgroup/web-ui"))
                .isEqualTo("web-ui");
    }

    @Test
    void deriveRepoName_handlesSshUrl() {
        assertThat(RepoNameUtil.deriveRepoName("git@github.com:org/repo.git")).isEqualTo("repo");
    }

    @Test
    void deriveRepoName_handlesSshUrl_noOrgPrefix() {
        // Corner case: SSH-style URL with no "/" separator between host and repo.
        assertThat(RepoNameUtil.deriveRepoName("git@host:repo.git")).isEqualTo("repo");
    }

    @Test
    void deriveRepoName_handlesTrailingSlash() {
        assertThat(RepoNameUtil.deriveRepoName("https://github.com/org/repo/")).isEqualTo("repo");
    }

    @Test
    void deriveRepoName_returnsEmptyForNull() {
        assertThat(RepoNameUtil.deriveRepoName(null)).isEmpty();
    }

    @Test
    void deriveRepoName_returnsEmptyForBlank() {
        assertThat(RepoNameUtil.deriveRepoName("   ")).isEmpty();
    }

    @Test
    void deriveRepoName_handlesBareName() {
        // If the URL has no slashes or colons, return the string itself (after stripping .git).
        assertThat(RepoNameUtil.deriveRepoName("mylocalrepo.git")).isEqualTo("mylocalrepo");
    }

    @Test
    void deriveRepoName_handlesSshUrlWithTrailingDotGit() {
        // Explicit coverage of the "SSH + .git suffix" combination (review A6).
        assertThat(RepoNameUtil.deriveRepoName("git@gitlab.example.com:team/subteam/service.git"))
                .isEqualTo("service");
    }

    @Test
    void deriveRepoName_stripsQueryString() {
        // Some hosts return URLs like https://.../repo.git?ref=main — the query should be
        // ignored when deriving a display name.
        assertThat(RepoNameUtil.deriveRepoName("https://git.example.com/org/repo.git?ref=main"))
                .isEqualTo("repo");
    }

    @Test
    void deriveRepoName_stripsQueryStringOnBareName() {
        assertThat(RepoNameUtil.deriveRepoName("https://git.example.com/org/repo?token=abc"))
                .isEqualTo("repo");
    }

    @Test
    void deriveOwnerRepoName_httpsUrl() {
        assertThat(RepoNameUtil.deriveOwnerRepoName("https://github.com/org/backend-api.git"))
                .isEqualTo("org/backend-api");
    }

    @Test
    void deriveOwnerRepoName_sshUrl() {
        assertThat(RepoNameUtil.deriveOwnerRepoName("git@github.com:org/web-ui.git"))
                .isEqualTo("org/web-ui");
    }

    @Test
    void deriveOwnerRepoName_subgroupKeepsImmediateOwner() {
        // For nested URLs we keep the *closest* parent segment; deeper hierarchies aren't
        // needed for collision-resistance and would surprise consumers.
        assertThat(RepoNameUtil.deriveOwnerRepoName("https://gitlab.com/group/subgroup/svc"))
                .isEqualTo("subgroup/svc");
    }

    @Test
    void deriveOwnerRepoName_handlesTrailingSlashAndDotGit() {
        assertThat(RepoNameUtil.deriveOwnerRepoName("https://github.com/org/repo.git/"))
                .isEqualTo("org/repo");
    }

    @Test
    void deriveOwnerRepoName_stripsQueryString() {
        assertThat(RepoNameUtil.deriveOwnerRepoName("https://git.example.com/org/repo.git?ref=main"))
                .isEqualTo("org/repo");
    }

    @Test
    void deriveOwnerRepoName_bareNameFallsBackToSingleSegment() {
        assertThat(RepoNameUtil.deriveOwnerRepoName("mylocalrepo.git")).isEqualTo("mylocalrepo");
    }

    @Test
    void deriveOwnerRepoName_returnsEmptyForNullOrBlank() {
        assertThat(RepoNameUtil.deriveOwnerRepoName(null)).isEmpty();
        assertThat(RepoNameUtil.deriveOwnerRepoName("   ")).isEmpty();
    }
}
