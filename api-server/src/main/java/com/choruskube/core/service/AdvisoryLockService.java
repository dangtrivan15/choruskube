package com.choruskube.core.service;

import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * LockService implementation backed by PostgreSQL transaction-level advisory locks.
 *
 * <p>Uses pg_advisory_xact_lock, which is automatically released at transaction end
 * — no manual unlock is required or possible.
 *
 * <p>The org UUID is hashed to a stable int8 key via md5 to satisfy the advisory-lock
 * API. The hash is collision-resistant for the expected number of orgs.
 */
@Service
public class AdvisoryLockService implements LockService {

    private final EntityManager entityManager;

    public AdvisoryLockService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Acquires a PostgreSQL transaction-level advisory lock for the given org.
     * MANDATORY propagation ensures the lock is held inside an existing transaction
     * and released atomically with it.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void acquireOrgRunLock(UUID orgId) {
        entityManager
                .createNativeQuery(
                        "SELECT pg_advisory_xact_lock(" + "('x' || substring(md5(?), 1, 16))::bit(64)::bigint" + ")")
                .setParameter(1, orgId.toString())
                .getSingleResult();
    }
}
