package com.choruskube.core.dto;

/**
 * One artifact a node declares it needs from an upstream node.
 *
 * <p>{@code required} defaults to false when a declaration omits it. An unflagged entry must not
 * harden into a pod abort: several declarations legitimately reference a prior iteration that does
 * not exist on iteration 1.
 */
public record ResolvedArtifactEntry(String name, String description, boolean required) {}
