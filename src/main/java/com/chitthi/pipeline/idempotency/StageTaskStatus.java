package com.chitthi.pipeline.idempotency;

/**
 * PENDING means "not currently owned, safe to claim or take over"; RUNNING
 * means a worker holds the lease named by {@code lease_until}; DONE means the
 * call already happened and {@code result} is the answer to give without
 * calling Sarvam again.
 */
public enum StageTaskStatus {
    PENDING,
    RUNNING,
    DONE
}
