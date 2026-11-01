package com.choruskube.core.service;

import java.util.UUID;

/**
 * Runs request-less work on a Story's behalf in the scope it needs; core binds nothing. Same
 * contract as {@link RunScopeBinder}, for work that has no run yet: resolve the scope from {@code
 * storyId} alone (throw if it cannot be bound), restore the thread's previous scope in a {@code
 * finally}, let exceptions propagate, and open no transaction around {@code work}.
 */
public interface StoryScopeBinder {

    void runInScopeOf(UUID storyId, Runnable work);
}
