package com.chitthi.cap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * FR15: one row per (owner, calendar day), tracking how many words that
 * owner has processed that day against the configured cap. Only ever
 * written through {@link UserDailyUsageRepository#tryClaim}'s atomic
 * upsert - see there for why a plain read-then-write in Java would be
 * unsafe under concurrent claims.
 */
@Entity
@Table(name = "user_daily_usage")
public class UserDailyUsage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private String ownerId;

    @Column(nullable = false)
    private LocalDate day;

    @Column(nullable = false)
    private int words;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected UserDailyUsage() {
    }

    public UUID getId() {
        return id;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public LocalDate getDay() {
        return day;
    }

    public int getWords() {
        return words;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
