package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.Autopilot;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements themselves, against real Postgres.
 *
 * <p>This class used to write the row with {@code saveAndFlush} and assert that {@code @PreUpdate}
 * bumped {@code updated_at}. Neither exists any more: the repository exposes no save, and bulk JPQL
 * bypasses lifecycle callbacks — so the timestamp is now the statements' own responsibility, and
 * {@link #everyModifyingStatementBumpsUpdatedAt()} is the guard that says so for all of them rather
 * than for whichever one a test happened to pick.
 */
@Transactional
public class AutopilotRepositoryTest extends BaseTest {

    private static final String OWNER = "instance-a";

    @Autowired
    private AutopilotRepository repo;

    @Test
    void insertDefaults_createsTheNeverConfiguredShapeFromTheDdlDefaults() {
        UUID id = UUID.randomUUID();

        assertThat(repo.insertDefaults(id)).isEqualTo(1);

        Autopilot created = repo.findById(id).orElseThrow();
        assertThat(created.isEngaged()).isFalse();
        assertThat(created.getMaxParallel()).isEqualTo(1);
        assertThat(created.getConsecutiveFailures()).isZero();
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getUpdatedAt()).isNotNull();
        assertThat(created.getTickOwner()).isNull();
        assertThat(created.getTickLeaseUntil()).isNull();
    }

    /**
     * Every statement that updates the row must move {@code updated_at} — and the set is derived
     * from the interface, so adding a statement without covering it here fails rather than passes
     * silently. With {@code @PreUpdate} gone there is nothing else to catch the omission, and a row
     * that claims it was last touched at insert time is wrong forever.
     */
    @Test
    void everyModifyingStatementBumpsUpdatedAt() {
        UUID id = insertRow();
        Map<String, Statement> covered = statements();

        Set<String> declared = Arrays.stream(AutopilotRepository.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Modifying.class))
                .map(Method::getName)
                // insertDefaults writes the row rather than updating one; V14's column defaults
                // supply both timestamps, pinned by the test above.
                .filter(name -> !name.equals("insertDefaults"))
                .collect(Collectors.toSet());
        assertThat(covered.keySet())
                .as("a new @Modifying statement must be listed here — nothing else checks updated_at")
                .containsExactlyInAnyOrderElementsOf(declared);

        // Truncated to microseconds because the pgjdbc driver ROUNDS to the column's precision
        // rather than truncating: a sub-microsecond remainder of 500ns or more comes back one
        // microsecond LATER than it went in, and the parking assertion below then fails on a value
        // it wrote itself. It is invisible on macOS, where Instant.now() is already
        // microsecond-precise, and roughly a coin flip on Linux CI, where it is not.
        Instant longAgo = Instant.now().minus(Duration.ofDays(1)).truncatedTo(ChronoUnit.MICROS);
        covered.forEach((name, statement) -> {
            statement.prepare().accept(id);
            // Park the timestamp in the past through a statement already proven to write it, so
            // "did this one touch updated_at" is a question about this statement alone.
            repo.stampTick(id, longAgo);
            assertThat(repo.findById(id).orElseThrow().getUpdatedAt())
                    .as("%s: parked in the past before the statement runs", name)
                    // Safe only because longAgo is already truncated to the column's precision —
                    // see above; an untruncated value can come back later than it was written.
                    .isBeforeOrEqualTo(longAgo);

            assertThat(statement.run().applyAsInt(id))
                    .as("%s: must actually match the row, or this proves nothing", name)
                    .isEqualTo(1);
            assertThat(repo.findById(id).orElseThrow().getUpdatedAt())
                    .as("%s does not set updated_at", name)
                    .isAfter(longAgo);
        });
    }

    /** A statement, with whatever has to be true before it will match the row. */
    private record Statement(Consumer<UUID> prepare, ToIntFunction<UUID> run) {}

    private Map<String, Statement> statements() {
        Instant now = Instant.now();
        Map<String, Statement> statements = new LinkedHashMap<>();
        statements.put("engage", new Statement(noSetup(), id -> repo.engage(id, now)));
        statements.put("disengage", new Statement(noSetup(), id -> repo.disengage(id, now)));
        statements.put(
                "disengageWithReason", new Statement(noSetup(), id -> repo.disengageWithReason(id, "reason", now)));
        // Guarded on engaged, so it only matches a row somebody turned on first.
        statements.put(
                "disengageIfEngagedWithReason",
                new Statement(id -> repo.engage(id, now), id -> repo.disengageIfEngagedWithReason(id, "reason", now)));
        statements.put("setMaxParallel", new Statement(noSetup(), id -> repo.setMaxParallel(id, 3, now)));
        statements.put("addFailures", new Statement(noSetup(), id -> repo.addFailures(id, 1, now)));
        statements.put("resetFailures", new Statement(noSetup(), id -> repo.resetFailures(id, now)));
        statements.put("stampTick", new Statement(noSetup(), id -> repo.stampTick(id, now)));
        // The lease statements only match when their guard holds, so each says what it needs.
        statements.put(
                "acquireTickLease",
                new Statement(id -> repo.releaseTickLease(id, OWNER), id -> repo.acquireTickLease(id, OWNER, 300)));
        statements.put(
                "renewTickLease",
                new Statement(id -> repo.acquireTickLease(id, OWNER, 300), id -> repo.renewTickLease(id, OWNER, 300)));
        statements.put(
                "releaseTickLease",
                new Statement(id -> repo.acquireTickLease(id, OWNER, 300), id -> repo.releaseTickLease(id, OWNER)));
        return statements;
    }

    private static Consumer<UUID> noSetup() {
        return id -> {};
    }

    @Test
    void addFailures_incrementsInTheDatabaseRatherThanFromAValueTheCallerRead() {
        // The defect this design exists to remove, pinned at its narrowest. The caller here holds
        // a copy of the row taken when the counter was 0; a read-modify-write would put 1 back and
        // erase the two increments that landed in between. The statement adds instead, so it does
        // not — whatever the caller believed the old value was.
        UUID id = insertRow();
        Autopilot staleCopy = repo.findById(id).orElseThrow();
        assertThat(staleCopy.getConsecutiveFailures()).isZero();
        repo.addFailures(id, 1, Instant.now());
        repo.addFailures(id, 1, Instant.now());

        repo.addFailures(id, 1, Instant.now());

        assertThat(repo.findConsecutiveFailuresById(id)).contains(3);
    }

    @Test
    void resetFailures_clearsTheCounter() {
        UUID id = insertRow();
        repo.addFailures(id, 2, Instant.now());

        repo.resetFailures(id, Instant.now());

        assertThat(repo.findConsecutiveFailuresById(id)).contains(0);
    }

    /**
     * The safety valve's statement, and the whole reason it is not {@link
     * AutopilotRepository#disengageWithReason}. Its caller is a reconciler that meets the same
     * broken credential on every pass, so "already off" has to be decided by the statement rather
     * than by a read the caller took first — otherwise each pass overwrites a reason a human is
     * reading and publishes a STOMP event announcing a stop that happened an hour ago.
     */
    @Test
    void disengageIfEngagedWithReason_matchesOnlyAnEngagedRow() {
        String first = "GitHub returned 401 for org/backend-api#42 — check the GitHub credential";
        UUID id = insertRow();
        assertThat(repo.findById(id).orElseThrow().isEngaged()).isFalse();

        assertThat(repo.disengageIfEngagedWithReason(id, first, Instant.now()))
                .as("nothing to stop, so nothing to report")
                .isZero();
        assertThat(repo.findById(id).orElseThrow().getDisengagedReason()).isNull();

        repo.engage(id, Instant.now());
        assertThat(repo.disengageIfEngagedWithReason(id, first, Instant.now())).isEqualTo(1);
        Autopilot after = repo.findById(id).orElseThrow();
        assertThat(after.isEngaged()).isFalse();
        assertThat(after.getDisengagedReason()).isEqualTo(first);
        assertThat(after.getConsecutiveFailures())
                .as("an external failure is not a run failure")
                .isZero();

        assertThat(repo.disengageIfEngagedWithReason(id, "a second pass, two minutes later", Instant.now()))
                .isZero();
        assertThat(repo.findById(id).orElseThrow().getDisengagedReason()).isEqualTo(first);
    }

    @Test
    void engage_thenDisengage_leavesNoFaultBanner() {
        UUID id = insertRow();
        repo.disengageWithReason(id, "Disengaged after 3 consecutive failures", Instant.now());
        assertThat(repo.findById(id).orElseThrow().getDisengagedReason()).isNotNull();

        repo.disengage(id, Instant.now());

        Autopilot after = repo.findById(id).orElseThrow();
        assertThat(after.isEngaged()).isFalse();
        assertThat(after.getDisengagedReason())
                .as("a human switching it off is not a fault")
                .isNull();
    }

    // -----------------------------------------------------------------------------------
    // The tick lease
    // -----------------------------------------------------------------------------------

    /**
     * The skew guard, and the only form it can take: there is no second clock to test against, so
     * what is asserted is that no caller can introduce one. A lease taking {@code now} and
     * {@code now + ttl} from its caller compares instance A's written expiry against instance B's
     * wall clock — B running ahead steals A's live lease, A learns of it only at its next renewal,
     * and both can have a start in flight until the skew goes away.
     */
    @Test
    void theLeaseStatementsAcceptNoCallerSuppliedClock() {
        Set<String> leaseStatements = Set.of("acquireTickLease", "renewTickLease", "releaseTickLease");

        assertThat(Arrays.stream(AutopilotRepository.class.getDeclaredMethods())
                        .filter(m -> leaseStatements.contains(m.getName()))
                        .flatMap(m -> Arrays.stream(m.getParameters()))
                        .map(Parameter::getType))
                .as("the lease's expiry must be judged by the database's clock and no other — a TTL "
                        + "in seconds is the only time a caller may supply")
                .doesNotContain(Instant.class);
    }

    @Test
    void acquireTickLease_isExclusiveWhileTheLeaseIsLive() {
        UUID id = insertRow();

        assertThat(repo.acquireTickLease(id, OWNER, 300)).isEqualTo(1);

        assertThat(repo.acquireTickLease(id, "instance-b", 300))
                .as("a second instance must be told to skip the pass, not queued behind it")
                .isZero();
        assertThat(repo.findById(id).orElseThrow().getTickOwner()).isEqualTo(OWNER);
    }

    @Test
    void acquireTickLease_reclaimsAnExpiredLease() {
        // The self-healing property. An instance that died mid-pass must not wedge the Autopilot.
        // A zero-second TTL expires the moment the database's clock moves on, which makes this
        // deterministic without a sleep — and only holds because expiry reads that clock.
        UUID id = insertRow();
        repo.acquireTickLease(id, "instance-that-died", 0);

        assertThat(repo.acquireTickLease(id, "instance-b", 300)).isEqualTo(1);
        assertThat(repo.findById(id).orElseThrow().getTickOwner()).isEqualTo("instance-b");
    }

    @Test
    void renewTickLease_failsOnceAnotherInstanceHasTakenOver() {
        UUID id = insertRow();
        repo.acquireTickLease(id, OWNER, 0);
        repo.acquireTickLease(id, "instance-b", 300);

        assertThat(repo.renewTickLease(id, OWNER, 300))
                .as("the overrunning instance must learn it no longer owns the pass")
                .isZero();
        assertThat(repo.renewTickLease(id, "instance-b", 300)).isEqualTo(1);
    }

    @Test
    void releaseTickLease_onlyReleasesTheCallersOwnLease() {
        UUID id = insertRow();
        repo.acquireTickLease(id, OWNER, 300);

        assertThat(repo.releaseTickLease(id, "instance-b")).isZero();
        assertThat(repo.releaseTickLease(id, OWNER)).isEqualTo(1);

        Autopilot after = repo.findById(id).orElseThrow();
        assertThat(after.getTickOwner()).isNull();
        assertThat(after.getTickLeaseUntil()).isNull();
    }

    private UUID insertRow() {
        UUID id = UUID.randomUUID();
        repo.insertDefaults(id);
        return id;
    }
}
