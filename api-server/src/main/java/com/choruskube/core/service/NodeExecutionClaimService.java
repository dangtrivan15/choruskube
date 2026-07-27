package com.choruskube.core.service;

import com.choruskube.core.model.enums.NodeExecutionStatus;
import com.choruskube.core.repository.NodeExecutionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin, dedicated transactional boundary around {@link NodeExecutionRepository#compareAndSetStatus}.
 *
 * <p>Spring Data does not wrap a custom {@code @Modifying @Query} method in a transaction on its
 * own — executing it with no ambient transaction active throws {@code TransactionRequiredException}.
 * {@link RunService#signalHumanDecision} is deliberately <em>not</em> itself {@code @Transactional}
 * (it signals the Temporal workflow mid-method and must not hold a database transaction open
 * across that network call). This service gives the compare-and-set call a transactional entry
 * point instead.
 *
 * <p>Deliberately plain {@code @Transactional} (propagation {@code REQUIRED}, the default) rather
 * than {@code REQUIRES_NEW}: {@code signalHumanDecision} never runs with an ambient transaction in
 * production, so {@code REQUIRED} always opens its own short-lived transaction there — identical
 * to {@code REQUIRES_NEW} in practice. But {@code REQUIRES_NEW} actively breaks under a
 * {@code @Transactional} test (the common Spring pattern for auto-rollback fixtures): it suspends
 * the test's transaction and runs on a separate connection, which can't see that transaction's
 * not-yet-committed seed rows, so the compare-and-set spuriously reports "not found" even though
 * the row is right there. {@code REQUIRED} joins whatever transaction is active — correct in both
 * worlds.
 */
@Service
public class NodeExecutionClaimService {

    private final NodeExecutionRepository execRepo;

    public NodeExecutionClaimService(NodeExecutionRepository execRepo) {
        this.execRepo = execRepo;
    }

    /**
     * Atomically flips a node execution's status from {@code expected} to {@code updated}.
     *
     * @return the number of rows updated — {@code 1} if this call won the race, {@code 0} if the
     *     node execution was not in {@code expected} status (already claimed by a concurrent
     *     caller, or genuinely not awaiting a decision)
     */
    @Transactional
    public int compareAndSetStatus(UUID id, NodeExecutionStatus expected, NodeExecutionStatus updated) {
        return execRepo.compareAndSetStatus(id, expected, updated);
    }
}
