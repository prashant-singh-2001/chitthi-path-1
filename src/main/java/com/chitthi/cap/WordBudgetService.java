package com.chitthi.cap;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * FR15's cost ceiling: 7,000 processed words per user per calendar day, the
 * day being local to {@link WordCapProperties#zone} rather than UTC, since
 * that is the boundary this product's users actually experience.
 */
@Service
public class WordBudgetService {

    private final UserDailyUsageRepository repository;
    private final WordCapProperties properties;
    private final MeterRegistry meterRegistry;

    public WordBudgetService(UserDailyUsageRepository repository, WordCapProperties properties,
                              MeterRegistry meterRegistry) {
        this.repository = repository;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Must be called from inside the caller's own transaction (see
     * {@link UserDailyUsageRepository#tryClaim}'s Javadoc) so the debit
     * commits or rolls back exactly together with whatever it's guarding.
     * {@code words <= 0} always succeeds without touching the ledger - there
     * is nothing to claim, and a blank OCR result must not fail the check
     * that decides whether that same page is even eligible to be marked
     * FAILED for a different reason.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryClaim(String ownerId, int words) {
        if (words <= 0) {
            return true;
        }
        int claimed = repository.tryClaim(ownerId, today(), words, properties.dailyWords());
        boolean allowed = claimed > 0;
        meterRegistry.counter("chitthi.cap.words", "outcome", allowed ? "allowed" : "rejected").increment(words);
        return allowed;
    }

    public int remainingWords(String ownerId) {
        int used = repository.findByOwnerIdAndDay(ownerId, today()).map(UserDailyUsage::getWords).orElse(0);
        return Math.max(0, properties.dailyWords() - used);
    }

    public int usedWords(String ownerId) {
        return repository.findByOwnerIdAndDay(ownerId, today()).map(UserDailyUsage::getWords).orElse(0);
    }

    public int dailyLimit() {
        return properties.dailyWords();
    }

    /** The instant the budget frees up: local midnight in {@link WordCapProperties#zone}, expressed as an offset. */
    public OffsetDateTime resetAt() {
        ZoneId zone = zone();
        return LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toOffsetDateTime();
    }

    private LocalDate today() {
        return LocalDate.now(zone());
    }

    private ZoneId zone() {
        return ZoneId.of(properties.zone());
    }
}
