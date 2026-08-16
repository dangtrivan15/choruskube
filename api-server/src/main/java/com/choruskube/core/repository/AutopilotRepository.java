package com.choruskube.core.repository;

import com.choruskube.core.model.Autopilot;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AutopilotRepository extends JpaRepository<Autopilot, UUID> {

    /**
     * The emergency stop, as one atomic statement.
     *
     * <p>Deliberately does NOT go through the tick's advisory lock. A stop queued behind an
     * in-flight tick would wait out a whole readiness sweep plus every container start, Temporal
     * round trips included — and a stop that appears to hang is a poor stop, since it is the one
     * control a user reaches for when something is already going wrong.
     *
     * <p>Skipping the lock is safe here and only here, because of what the two writers touch.
     * {@code Autopilot} is {@code @DynamicUpdate}, so a tick's write-back omits {@code engaged}
     * entirely unless it called {@code setEngaged} itself — and the only thing that does is the
     * failure breaker, which also writes {@code false}. There is therefore no interleaving in
     * which a tick turns the Autopilot back ON after a human turned it off. The same argument does
     * not extend to {@code engage()}, which shares {@code consecutive_failures} with the tick.
     *
     * <p>{@code disengaged_reason} is cleared for the same reason the service does it: a human
     * switching it off is not a fault, and the UI renders that field as a fault banner.
     *
     * @return rows affected — 0 when the row has been deleted underneath the caller
     */
    @Modifying
    @Query(
            value = "UPDATE autopilot SET engaged = false, disengaged_reason = NULL, updated_at = now() "
                    + "WHERE id = :id",
            nativeQuery = true)
    int disengage(@Param("id") UUID id);
}
