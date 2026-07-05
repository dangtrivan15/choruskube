package com.choruskube.core.repository;

import com.choruskube.core.model.LiveChatMessage;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LiveChatMessageRepository extends JpaRepository<LiveChatMessage, UUID> {

    List<LiveChatMessage> findBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    /**
     * Find messages created after the given timestamp.
     * Uses strict > to prevent poll loops from re-reading the same message.
     * Postgres microsecond precision makes same-timestamp collisions negligible.
     */
    List<LiveChatMessage> findBySessionIdAndCreatedAtGreaterThanOrderByCreatedAtAsc(UUID sessionId, Instant since);
}
