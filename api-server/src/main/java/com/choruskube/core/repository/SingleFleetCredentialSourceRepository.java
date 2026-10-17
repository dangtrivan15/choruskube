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
     * Upserts the declared source for {@code digest}. The {@code WHERE} clause makes a
     * same-value re-registration a no-op write rather than a no-op statement — it still runs,
     * just affects 0 rows, which is fine since nothing here reads the row count.
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
