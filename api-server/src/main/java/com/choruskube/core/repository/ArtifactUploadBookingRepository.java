package com.choruskube.core.repository;

import com.choruskube.core.model.ArtifactUploadBooking;
import com.choruskube.core.model.ArtifactUploadBookingId;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArtifactUploadBookingRepository extends JpaRepository<ArtifactUploadBooking, ArtifactUploadBookingId> {

    /**
     * Inserts the key's first booking, or raises an existing one to the larger of its current and
     * this size — never lowers it, so a still-valid URL signed for an earlier, larger size can
     * never outrun what the ledger counts (see the Story spec's booking decision).
     */
    @Modifying
    @Query(value = """
                    INSERT INTO artifact_upload_booking (workflow_run_id, object_key, booked_bytes, created_at, updated_at)
                    VALUES (:runId, :objectKey, :bytes, now(), now())
                    ON CONFLICT (workflow_run_id, object_key)
                    DO UPDATE SET booked_bytes = GREATEST(artifact_upload_booking.booked_bytes, EXCLUDED.booked_bytes),
                                  updated_at = now()
                    """, nativeQuery = true)
    void upsertKeepingLargest(
            @Param("runId") UUID runId, @Param("objectKey") String objectKey, @Param("bytes") long bytes);

    /** The run's booked total with one key left out, so admission can compare it against that key's new size. */
    @Query(
            value = "SELECT COALESCE(SUM(booked_bytes), 0) FROM artifact_upload_booking "
                    + "WHERE workflow_run_id = :runId AND object_key <> :objectKey",
            nativeQuery = true)
    long sumBookedBytesExcludingKey(@Param("runId") UUID runId, @Param("objectKey") String objectKey);

    @Query(
            value = "SELECT booked_bytes FROM artifact_upload_booking "
                    + "WHERE workflow_run_id = :runId AND object_key = :objectKey",
            nativeQuery = true)
    Optional<Long> findBookedBytes(@Param("runId") UUID runId, @Param("objectKey") String objectKey);

    @Query(
            value = "SELECT COALESCE(SUM(booked_bytes), 0) FROM artifact_upload_booking WHERE workflow_run_id = :runId",
            nativeQuery = true)
    long sumBookedBytes(@Param("runId") UUID runId);
}
