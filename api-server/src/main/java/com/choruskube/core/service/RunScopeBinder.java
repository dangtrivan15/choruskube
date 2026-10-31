package com.choruskube.core.service;

import java.util.UUID;

/**
 * Runs work on a run's behalf, from a caller with no tenant of its own, in the scope it needs; core
 * binds nothing. Resolve the scope from {@code runId} alone (throw if it cannot be bound), restore
 * the thread's previous scope in a {@code finally}, let exceptions propagate, and open no
 * transaction around {@code work}: the gate-finalize steps it runs refuse to start inside one.
 */
public interface RunScopeBinder {

    void runInScopeOf(UUID runId, Runnable work);
}
