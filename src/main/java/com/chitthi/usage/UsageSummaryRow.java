package com.chitthi.usage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Spring Data projection for {@link ApiCallRepository#summarizeByOwnerAndDay}. */
public interface UsageSummaryRow {
    OffsetDateTime getDay();

    UUID getDocumentId();

    String getTitle();

    Long getCalls();

    BigDecimal getCost();

    Double getP95();
}
