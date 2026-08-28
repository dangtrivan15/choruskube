package com.choruskube.core.dto;

import java.util.UUID;

/**
 * {@code type} is one of "git_repo" or "repo_group" — matches the {@code software_project.type}
 * column.
 */
public record SoftwareProjectRef(UUID id, String type, String name) {}
