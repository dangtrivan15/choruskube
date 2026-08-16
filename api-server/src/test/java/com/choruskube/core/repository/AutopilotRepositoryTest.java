package com.choruskube.core.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.choruskube.core.BaseTest;
import com.choruskube.core.model.Autopilot;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * The statements themselves, against real Postgres.
 *
 * <p>This class used to write the row with {@code saveAndFlush} and assert that {@code @PreUpdate}
 * bumped {@code updated_at}. Neither exists any more: the repository exposes no save, and bulk JPQL
 * bypasses lifecycle callbacks — so the timestamp is now the statements' own responsibility, and
 * that is what is checked here instead.
 */
@Transactional
public class AutopilotRepositoryTest extends BaseTest {

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

    @Test
    void everyStatement_bumpsUpdatedAt() {
        // Not cosmetic: with no @PreUpdate to fall back on, a statement that forgets this column
        // leaves the row claiming it was last touched at insert time, forever.
        UUID id = insertRow();
        Instant firstWrite = repo.findById(id).orElseThrow().getUpdatedAt();
        Instant later = firstWrite.plusSeconds(1);

        repo.engage(id, later);

        assertThat(repo.findById(id).orElseThrow().getUpdatedAt()).isAfter(firstWrite);
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

    @Test
    void acquireTickLease_isExclusiveWhileTheLeaseIsLive() {
        UUID id = insertRow();
        Instant now = Instant.now();

        assertThat(repo.acquireTickLease(id, "instance-a", now.plus(Duration.ofMinutes(5)), now))
                .isEqualTo(1);

        assertThat(repo.acquireTickLease(id, "instance-b", now.plus(Duration.ofMinutes(5)), now))
                .as("a second instance must be told to skip the pass, not queued behind it")
                .isZero();
        assertThat(repo.findById(id).orElseThrow().getTickOwner()).isEqualTo("instance-a");
    }

    @Test
    void acquireTickLease_reclaimsAnExpiredLease() {
        // The self-healing property. An instance that died mid-pass must not wedge the Autopilot.
        UUID id = insertRow();
        Instant longAgo = Instant.now().minus(Duration.ofHours(1));
        repo.acquireTickLease(id, "instance-that-died", longAgo.plusSeconds(1), longAgo);

        Instant now = Instant.now();
        assertThat(repo.acquireTickLease(id, "instance-b", now.plus(Duration.ofMinutes(5)), now))
                .isEqualTo(1);
        assertThat(repo.findById(id).orElseThrow().getTickOwner()).isEqualTo("instance-b");
    }

    @Test
    void renewTickLease_failsOnceAnotherInstanceHasTakenOver() {
        UUID id = insertRow();
        Instant longAgo = Instant.now().minus(Duration.ofHours(1));
        repo.acquireTickLease(id, "instance-a", longAgo.plusSeconds(1), longAgo);
        Instant now = Instant.now();
        repo.acquireTickLease(id, "instance-b", now.plus(Duration.ofMinutes(5)), now);

        assertThat(repo.renewTickLease(id, "instance-a", now.plus(Duration.ofMinutes(5)), now))
                .as("the overrunning instance must learn it no longer owns the pass")
                .isZero();
        assertThat(repo.renewTickLease(id, "instance-b", now.plus(Duration.ofMinutes(10)), now))
                .isEqualTo(1);
    }

    @Test
    void releaseTickLease_onlyReleasesTheCallersOwnLease() {
        UUID id = insertRow();
        Instant now = Instant.now();
        repo.acquireTickLease(id, "instance-a", now.plus(Duration.ofMinutes(5)), now);

        assertThat(repo.releaseTickLease(id, "instance-b", now)).isZero();
        assertThat(repo.releaseTickLease(id, "instance-a", now)).isEqualTo(1);

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
