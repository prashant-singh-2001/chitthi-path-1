package com.chitthi.pipeline.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Guards one paid Sarvam call per idempotency key: {@link #callOnce} either
 * returns a previously-stored result with no call at all, or runs
 * {@code call} itself, having first proven (via {@code stage_task}'s unique
 * constraint) that no one else is running it concurrently.
 *
 * <p>Each database step is its own short transaction (see
 * {@link StageTaskRepository}'s {@code @Transactional} methods) - none of
 * them wraps {@code call}, since that is an external, possibly slow HTTP
 * call, the same reasoning {@code TranslateWorker} and {@code TtsWorker}
 * already follow for the pipeline's other database writes.
 */
@Service
public class StageTaskService {

    private static final Logger log = LoggerFactory.getLogger(StageTaskService.class);

    private final StageTaskRepository repository;
    private final Duration lease;

    public StageTaskService(StageTaskRepository repository,
                             @Value("${chitthi.stage-task.lease:90s}") Duration lease) {
        this.repository = repository;
        this.lease = lease;
    }

    /**
     * @param idempotencyKey deterministic per (page, stage, track, chunk, content hash)
     * @param call           the paid Sarvam call to make, only if no stored result already answers it
     * @throws StageTaskBusyException if another worker currently holds a live lease on this key
     */
    public String callOnce(String idempotencyKey, UUID pageId, String stage, Supplier<String> call) {
        Optional<String> alreadyDone = claim(idempotencyKey, pageId, stage);
        if (alreadyDone.isPresent()) {
            return alreadyDone.get();
        }
        try {
            String result = call.get();
            repository.markDone(idempotencyKey, result);
            return result;
        } catch (RuntimeException e) {
            log.warn("stage_task {} failed; leaving it retryable", idempotencyKey, e);
            repository.markFailed(idempotencyKey, truncate(e.getMessage()));
            throw e;
        }
    }

    /**
     * @return the stored result if the key is already DONE (no call needed);
     *         empty if this call now owns the key's lease and should run it.
     */
    private Optional<String> claim(String idempotencyKey, UUID pageId, String stage) {
        OffsetDateTime leaseUntil = OffsetDateTime.now().plus(lease);
        int inserted = repository.insertIfAbsent(pageId, stage, idempotencyKey, leaseUntil);
        if (inserted > 0) {
            return Optional.empty();
        }

        StageTask existing = repository.findByIdempotencyKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("stage_task vanished after a failed insert: " + idempotencyKey));
        if (existing.getStatus() == StageTaskStatus.DONE) {
            return Optional.of(existing.getResult());
        }

        int takenOver = repository.takeOverIfAvailable(idempotencyKey, leaseUntil, OffsetDateTime.now());
        if (takenOver == 0) {
            throw new StageTaskBusyException(idempotencyKey);
        }
        return Optional.empty();
    }

    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}
