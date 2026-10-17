package com.choruskube.core.repository;

import com.choruskube.core.model.SingleFleetCredentialSource;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SingleFleetCredentialSourceRepository extends JpaRepository<SingleFleetCredentialSource, String> {

    /**
     * Upserts the declared source for {@code digest}. Workers re-register every minute, so the
     * {@code WHERE} clause skips the write when the source is unchanged; the returned count is 0
     * then, and is not a failure signal.
     */
    @Modifying
    @Transactional
    @Query(
            value =
                    "INSERT INTO single_fleet_credential_source (credential_digest, credential_source, instance_id, declared_at) "
                            + "VALUES (:digest, :source, :instanceId, now()) "
                            + "ON CONFLICT (credential_digest) DO UPDATE SET credential_source = EXCLUDED.credential_source, "
                            + "instance_id = EXCLUDED.instance_id, declared_at = EXCLUDED.declared_at "
                            + "WHERE single_fleet_credential_source.credential_source <> EXCLUDED.credential_source",
            nativeQuery = true)
    int record(@Param("digest") String digest, @Param("source") String source, @Param("instanceId") UUID instanceId);
}
