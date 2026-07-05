package com.choruskube.core.service;

import static org.assertj.core.api.Assertions.*;

import com.choruskube.core.BaseTest;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for AdvisoryLockService using a real PostgreSQL instance (via Testcontainers).
 *
 * <p>Verifies:
 * <ol>
 *   <li>Acquiring a lock inside a transaction succeeds without throwing.
 *   <li>Calling acquireOrgRunLock outside a transaction throws due to MANDATORY propagation.
 *   <li>Re-acquiring the same advisory lock within a single transaction is idempotent in Postgres.
 *   <li>Two concurrent callers with the same org ID serialize — one waits until the other commits.
 * </ol>
 */
class AdvisoryLockServiceTest extends BaseTest {

    @Autowired
    private AdvisoryLockService advisoryLockService;

    @Autowired
    private PlatformTransactionManager txManager;

    // -----------------------------------------------------------------------
    // Happy-path: lock acquired within an active transaction
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void acquireOrgRunLock_withinTransaction_succeeds() {
        UUID orgId = UUID.randomUUID();
        assertThatCode(() -> advisoryLockService.acquireOrgRunLock(orgId)).doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // MANDATORY propagation: throws when no transaction is active
    // -----------------------------------------------------------------------

    @Test
    void acquireOrgRunLock_outsideTransaction_throwsIllegalTransactionState() {
        UUID orgId = UUID.randomUUID();
        // Spring AOP rejects the call before executing the method body
        assertThatThrownBy(() -> advisoryLockService.acquireOrgRunLock(orgId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // -----------------------------------------------------------------------
    // Idempotency: re-acquiring the same lock in the same transaction succeeds
    // -----------------------------------------------------------------------

    @Test
    @Transactional
    void acquireOrgRunLock_sameOrgTwiceInOneTransaction_isIdempotent() {
        UUID orgId = UUID.randomUUID();
        assertThatCode(() -> {
                    advisoryLockService.acquireOrgRunLock(orgId);
                    advisoryLockService.acquireOrgRunLock(orgId);
                })
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------------
    // Concurrency: second caller blocks until the first transaction commits
    // -----------------------------------------------------------------------

    @Test
    void acquireOrgRunLock_concurrentCallers_serializeExecution() throws Exception {
        UUID orgId = UUID.randomUUID();
        TransactionTemplate txTemplate = new TransactionTemplate(txManager);

        // Timeline tracking — record when each thread acquired the lock, and when thread 1 was
        // signalled to release (to prove thread 2 actually blocked rather than running concurrently)
        AtomicLong thread1AcquiredAt = new AtomicLong();
        AtomicLong thread2AcquiredAt = new AtomicLong();
        AtomicLong thread1ReleaseSignalledAt = new AtomicLong();

        CountDownLatch thread1Holding = new CountDownLatch(1);
        CountDownLatch thread1Released = new CountDownLatch(1);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: acquires lock, signals it is holding, then waits to be told to commit
        Future<?> future1 = executor.submit(() -> txTemplate.execute((TransactionStatus status) -> {
            advisoryLockService.acquireOrgRunLock(orgId);
            thread1AcquiredAt.set(System.nanoTime());
            thread1Holding.countDown(); // signal: "I have the lock"
            try {
                thread1Released.await(5, TimeUnit.SECONDS); // hold for up to 5 s
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null; // commit
        }));

        // Wait until thread 1 is definitely holding the lock before starting thread 2
        assertThat(thread1Holding.await(5, TimeUnit.SECONDS))
                .as("Thread 1 should acquire the lock within 5 seconds")
                .isTrue();

        // Thread 2: must wait for thread 1's transaction to commit before it can acquire
        Future<?> future2 = executor.submit(() -> txTemplate.execute((TransactionStatus status) -> {
            advisoryLockService.acquireOrgRunLock(orgId); // blocks until thread 1 commits
            thread2AcquiredAt.set(System.nanoTime());
            return null;
        }));

        // Release thread 1 (commit its transaction); record the moment we signalled the release
        thread1ReleaseSignalledAt.set(System.nanoTime());
        thread1Released.countDown();

        future1.get(10, TimeUnit.SECONDS);
        future2.get(10, TimeUnit.SECONDS);

        executor.shutdown();

        // Thread 2 must have acquired the lock AFTER thread 1 was signalled to release.
        // This proves actual blocking: if acquireOrgRunLock were a no-op, thread 2 would record
        // its timestamp before thread1ReleaseSignalledAt was set, failing this assertion.
        assertThat(thread2AcquiredAt.get())
                .as("Thread 2 should acquire the lock only after thread 1's transaction commits")
                .isGreaterThan(thread1ReleaseSignalledAt.get());
    }
}
