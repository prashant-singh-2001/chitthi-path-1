package com.chitthi.usage;

import java.math.BigDecimal;

/** Spring Data projection for {@link ApiCallRepository#summarizeByDocument} - getter names match the query's column aliases. */
public interface EndpointUsageRow {
    String getEndpoint();

    Long getCalls();

    Long getUnits();

    BigDecimal getCost();

    Double getP50();

    Double getP95();
}
