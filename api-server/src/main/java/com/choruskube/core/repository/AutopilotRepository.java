package com.choruskube.core.repository;

import com.choruskube.core.model.Autopilot;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write to the Autopilot row, and every one of them a single statement.
 *
 * <p><strong>Deliberately not a {@code JpaRepository}.</strong> That is the whole design: this
 * interface exposes no {@code save}, no {@code saveAndFlush}, no {@code merge}, so there is no way
 * to write this row by reading it, changing a field and writing it back. Three rounds of
 * concurrency fixes on this table were all the same defect at different offsets — a
 * read-modify-write over a long transaction, arbitrated with locks and orderings that cannot
 * express "this write is valid only if nobody changed the row since I read it". Removing the
 * read-modify-write removes the class of defect; removing the method that makes one possible is
 * what stops it coming back. Re-adding {@code extends JpaRepository} here is a compile-time-visible
 * decision, not an accident, and {@code AutopilotEntityWriteGuardTest} fails loudly if it happens.
 *
 * <p>Two consequences worth stating:
 *
 * <ul>
 *   <li>{@link #addFailures} increments <em>in the database</em>. Two replicas settling one failed
 *       run each produce 2, where a read-increment-write would lose one of them.
 *   <li>Every statement carries {@code updated_at} explicitly. Bulk JPQL bypasses lifecycle
 *       callbacks, so {@code Autopilot} has none — a {@code @PreUpdate} here would silently never
 *       fire.
 * </ul>
 *
 * <p>{@code flushAutomatically} + {@code clearAutomatically} on every statement is what makes
 * "write, then re-read" correct: the flush lets a statement co-exist with pending changes to other
 * entities in the same transaction (the settle batch's {@code workflow_run} rows), and the clear
 * stops a later {@code findById} handing back the pre-statement copy out of the persistence
 * context. It replaces the {@code entityManager.refresh} calls the previous design needed.
 */
public interface AutopilotRepository extends Repository<Autopilot, UUID> {

    List<Autopilot> findAll();

    Optional<Autopilot> findById(UUID id);

    long count();

    /** {@code engaged} alone — the per-start re-check in phase 3 runs once per Task started. */
    @Query("SELECT a.engaged FROM Autopilot a WHERE a.id = :id")
    Optional<Boolean> findEngagedById(@Param("id") UUID id);

    /** The slot ceiling alone, for the planning phase, which holds no entity. */
    @Query("SELECT a.maxParallel FROM Autopilot a WHERE a.id = :id")
    Optional<Integer> findMaxParallelById(@Param("id") UUID id);

    /**
     * The counter after someone else's increment as well as our own. A scalar projection rather
     * than {@code findById}, so the answer can never be served from the persistence context.
     */
    @Query("SELECT a.consecutiveFailures FROM Autopilot a WHERE a.id = :id")
    Optional<Integer> findConsecutiveFailuresById(@Param("id") UUID id);

    /**
     * The row, at its column defaults. The id is chosen by the caller because there is no
     * identifier generator on an entity nothing persists; everything else comes from V14's
     * {@code DEFAULT} clauses, so the "never configured" shape is defined in exactly one place.
     *
     * <p>Callers layer the mutation they actually wanted on top — {@code insertDefaults} then
     * {@code engage} — rather than this taking a parameter per column.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO autopilot (id) VALUES (:id)", nativeQuery = true)
    int insertDefaults(@Param("id") UUID id);

    /** Turns it on and clears the failure state a human has presumably just fixed. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Autopilot a SET a.engaged = true, a.consecutiveFailures = 0, a.disengagedReason = null, "
            + "a.lastTickAt = :now, a.updatedAt = :now WHERE a.id = :id")
    int engage(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * The emergency stop. {@code disengaged_reason} is cleared because a human switching it off is
     * not a fault, and the UI renders that field as a fault banner.
     *
     * <p>It waits for nothing but the row lock on this one row, which no tick phase holds for
     * longer than a statement. Nothing can undo it either: the tick no longer writes this row
     * through an entity, so there is no stale write-back left to restore {@code engaged = true}.
     *
     * @return rows affected — 0 when the row has been deleted underneath the caller
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Autopilot a SET a.engaged = false, a.disengagedReason = null, a.updatedAt = :now "
            + "WHERE a.id = :id")
    int disengage(@Param("id") UUID id, @Param("now") Instant now);

    /** The failure breaker's stop, which keeps a reason a human can act on. */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Autopilot a SET a.engaged = false, a.disengagedReason = :reason, a.updatedAt = :now "
            + "WHERE a.id = :id")
    int disengageWithReason(@Param("id") UUID id, @Param("reason") String reason, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Autopilot a SET a.maxParallel = :maxParallel, a.updatedAt = :now WHERE a.id = :id")
    int setMaxParallel(@Param("id") UUID id, @Param("maxParallel") int maxParallel, @Param("now") Instant now);

    /**
     * Adds to the failure counter <strong>in the database</strong>.
     *
     * <p>The read-increment-write this replaces is the original defect of the whole table: two
     * replicas that both read 0 both write 1, and one real failure disappears. Here the arithmetic
     * happens inside the statement, so the outcome is 2 whatever the interleaving, and a caller
     * holding a stale copy of the row cannot corrupt it because it never supplies the old value.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Autopilot a SET a.consecutiveFailures = a.consecutiveFailures + :delta, a.updatedAt = :now "
            + "WHERE a.id = :id")
    int addFailures(@Param("id") UUID id, @Param("delta") int delta, @Param("now") Instant now);

    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Autopilot a SET a.consecutiveFailures = 0, a.updatedAt = :now WHERE a.id = :id")
    int resetFailures(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Records that a pass ran. Stamped in phase 1 rather than at the end of the tick, so a pass
     * that dies in the middle still says when it last ran — the panel reads "last tick" as
     * liveness, and an Autopilot that keeps crashing must not look idle.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Autopilot a SET a.lastTickAt = :now, a.updatedAt = :now WHERE a.id = :id")
    int stampTick(@Param("id") UUID id, @Param("now") Instant now);

    // -----------------------------------------------------------------------------------
    // The tick lease — ownership of one whole pass
    // -----------------------------------------------------------------------------------

    /**
     * Claims the next pass for {@code owner}, but only if nobody holds a live lease.
     *
     * <p>This replaced {@code pg_advisory_xact_lock} in the tick, and had to. That lock is
     * transaction-scoped: once the tick became four short transactions it was released as soon as
     * the settle phase committed, so planning, starting and reporting ran unprotected and two
     * instances would each compute the same free slots and each start work — {@code max_parallel}
     * violated, which is the guarantee the whole feature is sold on. Re-checking slots per start
     * does not help, because both instances re-check concurrently and both still see capacity.
     *
     * <p>The condition and the write are one statement, so two instances racing here cannot both
     * win: Postgres serialises the row update and the loser's {@code WHERE} no longer holds.
     *
     * @return 1 when the caller owns the pass, 0 when someone else is already ticking — in which
     *     case the caller must return immediately rather than wait. A skipped pass costs one
     *     scheduler interval; a queued one would pile instances up behind a slow tick.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Autopilot a SET a.tickOwner = :owner, a.tickLeaseUntil = :leaseUntil, a.updatedAt = :now "
            + "WHERE a.id = :id AND (a.tickLeaseUntil IS NULL OR a.tickLeaseUntil < :now)")
    int acquireTickLease(
            @Param("id") UUID id,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now);

    /**
     * Extends a lease this instance still holds — called between phases and before every start.
     *
     * @return 0 when the pass overran its lease and another instance has since taken over. The
     *     caller must then abandon the pass without starting anything further. That is not a
     *     failure and must not reach the breaker: the work is fine, this instance simply stopped
     *     being the one allowed to do it.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Autopilot a SET a.tickLeaseUntil = :leaseUntil, a.updatedAt = :now "
            + "WHERE a.id = :id AND a.tickOwner = :owner AND a.tickLeaseUntil >= :now")
    int renewTickLease(
            @Param("id") UUID id,
            @Param("owner") String owner,
            @Param("leaseUntil") Instant leaseUntil,
            @Param("now") Instant now);

    /**
     * Hands the lease back at the end of a pass, so the next scheduler interval can start one
     * rather than waiting out the TTL.
     *
     * <p>Guarded on ownership, so an instance whose lease already expired and was taken by someone
     * else releases nothing. Missing this call entirely costs at most one TTL of idleness — the
     * self-healing property that a session-scoped lock would not have had.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Autopilot a SET a.tickOwner = null, a.tickLeaseUntil = null, a.updatedAt = :now "
            + "WHERE a.id = :id AND a.tickOwner = :owner")
    int releaseTickLease(@Param("id") UUID id, @Param("owner") String owner, @Param("now") Instant now);
}
