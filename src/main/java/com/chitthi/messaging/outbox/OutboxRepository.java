package com.chitthi.messaging.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * {@code SKIP LOCKED} so the relay's own sweep interval never blocks a
     * second sweep still holding rows from a slow publish - same reasoning
     * as {@code OcrBatchRepository}'s claim queries.
     */
    @Query(value = "SELECT * FROM outbox WHERE published_at IS NULL ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxMessage> lockUnpublished(@Param("limit") int limit);

    long deleteByPublishedAtBefore(OffsetDateTime cutoff);

    /** Backs the {@code chitthi.outbox.unpublished} gauge - the same partial index {@link #lockUnpublished} uses makes this cheap. */
    long countByPublishedAtIsNull();
}
