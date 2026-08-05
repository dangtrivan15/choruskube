package com.choruskube.core.dto;

import java.util.List;
import java.util.Map;

/**
 * Files to materialise under {@code /workspace/in/} before an agent starts.
 *
 * @param artifacts key → object storage path, where key is {@code <source_label>/<filename>} and
 *     becomes the path under {@code /workspace/in/}
 * @param required the subset of keys whose absence must fail the node rather than be skipped
 */
public record InputArtifactManifest(Map<String, String> artifacts, List<String> required) {}
