package com.choruskube.core.service;

import java.util.UUID;

/**
 * Abstraction for acquiring distributed/coordination locks.
 * Implementations must hold the lock for the duration of the current transaction.
 */
public interface LockService {

    /**
     * Acquires an exclusive lock scoped to the given organisation for the purpose
     * of serialising concurrent run-start operations.
     *
     * <p>Blocks until the lock is available. The lock is released automatically
     * when the surrounding transaction commits or rolls back.
     *
     * <p>Must be called from within an active transaction.
     *
     * @param orgId the organisation whose run-start operations should be serialised
     */
    void acquireOrgRunLock(UUID orgId);
}
