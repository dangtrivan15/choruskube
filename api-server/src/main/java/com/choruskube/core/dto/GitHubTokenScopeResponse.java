package com.choruskube.core.dto;

import java.util.List;
import java.util.Map;

/** The {@code GET /worker/runs/{runId}/github-token-scope} response body. Makes no GitHub call. */
public record GitHubTokenScopeResponse(List<String> repositories, Map<String, String> permissions) {}
