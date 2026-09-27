package com.chitthi.cap;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface UserDailyUsageRepository extends JpaRepository<UserDailyUsage, UUID> {

    Optional<UserDailyUsage> findByOwnerIdAndDay(String ownerId, LocalDate day);

    /**
     * The atomic claim: on a first claim for the day, the {@code SELECT}'s
     * own {@code WHERE} rejects a claim bigger than the whole cap (a plain
     * {@code VALUES} insert has nowhere to put that guard); on every claim
     * after the first, {@code ON CONFLICT ... DO UPDATE}'s {@code WHERE}
     * rejects one that would push the existing total over the cap. Either
     * way, {@code 0} rows affected means "you lost" - Postgres' unique index
     * on (owner_id, day) is what makes that true under concurrent callers,
     * not any locking done in Java. Must run in the caller's own transaction
     * (never {@code REQUIRES_NEW}) so a claim commits or rolls back exactly
     * together with the page-status change it's guarding - unlike
     * {@code UsageRecorder}, which deliberately isolates and swallows ledger
     * failures because metering must never fail a paid call, a cap claim has
     * to be authoritative.
     *
     * <p>The {@code CAST}s are load-bearing: Postgres cannot infer a bare
     * JDBC parameter's type in a {@code SELECT} with no {@code FROM}, and
     * fails with "could not determine data type of parameter" without them.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Modifying
    @Query(value = "INSERT INTO user_daily_usage (owner_id, day, words) "
            + "SELECT CAST(:ownerId AS VARCHAR), CAST(:day AS DATE), CAST(:words AS INTEGER) "
            + "WHERE CAST(:words AS INTEGER) <= CAST(:cap AS INTEGER) "
            + "ON CONFLICT (owner_id, day) DO UPDATE "
            + "SET words = user_daily_usage.words + EXCLUDED.words "
            + "WHERE user_daily_usage.words + EXCLUDED.words <= CAST(:cap AS INTEGER)", nativeQuery = true)
    int tryClaim(@Param("ownerId") String ownerId, @Param("day") LocalDate day,
                 @Param("words") int words, @Param("cap") int cap);
}
