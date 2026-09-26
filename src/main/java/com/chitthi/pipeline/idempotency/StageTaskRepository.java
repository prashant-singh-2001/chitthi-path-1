package com.chitthi.pipeline.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface StageTaskRepository extends JpaRepository<StageTask, UUID> {

    Optional<StageTask> findByIdempotencyKey(String idempotencyKey);

    /**
     * The actual compare-and-set for a brand-new key: {@code ON CONFLICT DO
     * NOTHING} means exactly one caller ever gets {@code 1} back for a given
     * key, no matter how many race to insert it at once - Postgres' unique
     * index is what makes that true, not any locking done in Java.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO stage_task (page_id, stage, idempotency_key, attempts, status, lease_until) "
            + "VALUES (:pageId, :stage, :idempotencyKey, 1, 'RUNNING', :leaseUntil) "
            + "ON CONFLICT (idempotency_key) DO NOTHING", nativeQuery = true)
    int insertIfAbsent(@Param("pageId") UUID pageId, @Param("stage") String stage,
                        @Param("idempotencyKey") String idempotencyKey, @Param("leaseUntil") OffsetDateTime leaseUntil);

    /**
     * Takes over an existing row only if it is genuinely available: still
     * PENDING (a previous call failed and gave it back), or RUNNING with an
     * expired lease (the worker that claimed it crashed or is still mid-call
     * past its lease). A row RUNNING with a live lease matches neither
     * branch, so this affects no row and the caller must treat the key as
     * busy rather than call Sarvam a second time for work already in flight.
     */
    @Transactional
    @Modifying
    @Query("UPDATE StageTask t SET t.status = com.chitthi.pipeline.idempotency.StageTaskStatus.RUNNING, "
            + "t.leaseUntil = :leaseUntil, t.attempts = t.attempts + 1 "
            + "WHERE t.idempotencyKey = :idempotencyKey AND "
            + "(t.status = com.chitthi.pipeline.idempotency.StageTaskStatus.PENDING "
            + "OR (t.status = com.chitthi.pipeline.idempotency.StageTaskStatus.RUNNING AND t.leaseUntil < :now))")
    int takeOverIfAvailable(@Param("idempotencyKey") String idempotencyKey,
                             @Param("leaseUntil") OffsetDateTime leaseUntil, @Param("now") OffsetDateTime now);

    @Transactional
    @Modifying
    @Query("UPDATE StageTask t SET t.status = com.chitthi.pipeline.idempotency.StageTaskStatus.DONE, "
            + "t.result = :result WHERE t.idempotencyKey = :idempotencyKey")
    int markDone(@Param("idempotencyKey") String idempotencyKey, @Param("result") String result);

    /**
     * Gives the key back rather than leaving it RUNNING: a call that failed
     * outright (not merely paused by a rate limiter or circuit breaker,
     * which never reach this) should be retryable on the very next delivery,
     * not stuck waiting out a lease for no reason.
     */
    @Transactional
    @Modifying
    @Query("UPDATE StageTask t SET t.status = com.chitthi.pipeline.idempotency.StageTaskStatus.PENDING, "
            + "t.lastError = :error WHERE t.idempotencyKey = :idempotencyKey")
    int markFailed(@Param("idempotencyKey") String idempotencyKey, @Param("error") String error);
}
