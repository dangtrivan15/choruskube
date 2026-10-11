package com.choruskube.core.service;

import java.util.UUID;

/**
 * Runs request-less work on a run's behalf in the scope it needs; core binds nothing. Resolve the
 * scope from {@code runId} alone (throw if it cannot be bound), restore the thread's previous scope
 * in a {@code finally}, let exceptions propagate, and open no transaction around {@code work}: its
 * gate decision is deliberately non-transactional.
 */
public interface RunScopeBinder {

    void runInScopeOf(UUID runId, Runnable work);
}
