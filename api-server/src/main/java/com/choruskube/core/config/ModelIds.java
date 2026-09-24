package com.choruskube.core.config;

/**
 * Claude Code model aliases shared across template seeders.
 *
 * <p>Aliases resolve inside the Claude Code CLI, so the {@code CLAUDE_CODE_VERSION} pin in
 * {@code agent-images/claude-code/Dockerfile} decides which model each one runs. Move agents to
 * a newer model by bumping that pin; a full model ID here would freeze it past every bump.
 */
public final class ModelIds {
    public static final String MODEL_OPUS = "opus";
    public static final String MODEL_SONNET = "sonnet";

    private ModelIds() {}
}
