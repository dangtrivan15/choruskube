package com.choruskube.core.service;

import java.util.UUID;

public interface RepoUniquenessChecker {

    /**
     * Asserts that no other software project (git-repo) uses {@code url} within the relevant scope.
     *
     * @param url the candidate repo URL
     * @param excludeRepoId the repo being updated, excluded from the check; {@code null} on create
     * @throws com.choruskube.core.exception.ConflictException if the URL is already taken
     */
    void assertUrlAvailable(String url, UUID excludeRepoId);

    /**
     * Asserts that no software project (git-repo or repo-group) uses {@code name} within the relevant
     * scope.
     *
     * @param name the candidate project name
     * @throws com.choruskube.core.exception.BadRequestException if the name is already taken
     */
    void assertNameAvailable(String name);
}
