package com.choruskube.core.config;

/**
 * Canonical Claude model ID constants shared across template seeders.
 *
 * <p>Mirrors {@link GraphIds}'s shape (a small holder of {@code public static final String}
 * constants with a private no-arg constructor) so seeders reference one shared literal per
 * model instead of each declaring its own local copy.
 *
 * <p>These are the current, non-dated model ID strings (verified against the live model
 * catalog at implementation time, not guessed) — Claude's current-generation model IDs are
 * bare aliases with no {@code -YYYYMMDD} suffix; only superseded/legacy model IDs carry a
 * dated suffix. See Caveat 1 in the accompanying spec: a human should confirm the org's
 * Anthropic entitlement covers these exact IDs before rollout, and re-check this file against
 * the current model catalog if it has been a while since these were last verified.
 */
public final class ModelIds {
    public static final String MODEL_OPUS = "claude-opus-4-8";
    public static final String MODEL_SONNET = "claude-sonnet-5";

    private ModelIds() {}
}
