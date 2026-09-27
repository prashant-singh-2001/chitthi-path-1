-- FR15: the per-user daily word budget. (owner_id, day) is UNIQUE because
-- UserDailyUsageRepository.tryClaim needs it as an ON CONFLICT target - the
-- unique index is what makes the claim atomic, not any locking done in Java.
CREATE TABLE user_daily_usage (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id   VARCHAR(255) NOT NULL,
    day        DATE         NOT NULL,
    words      INTEGER      NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (owner_id, day)
);
