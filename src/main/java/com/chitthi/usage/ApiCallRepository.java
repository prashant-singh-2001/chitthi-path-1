package com.chitthi.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface ApiCallRepository extends JpaRepository<ApiCall, UUID> {

    /**
     * One row per endpoint the document actually called, with call count,
     * total units, total estimated cost, and p50/p95 latency -
     * {@code percentile_cont} does the percentile math in the database
     * rather than pulling every row back into Java.
     */
    @Query(value = """
            SELECT endpoint AS endpoint,
                   count(*) AS calls,
                   sum(units) AS units,
                   sum(est_cost_inr) AS cost,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) AS p50,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms) AS p95
            FROM api_call
            WHERE document_id = :documentId
            GROUP BY endpoint
            """, nativeQuery = true)
    List<EndpointUsageRow> summarizeByDocument(@Param("documentId") UUID documentId);

    /**
     * Spend and latency per document per day for one owner, the requirements
     * doc's {@code GET /api/usage} shape.
     */
    @Query(value = """
            SELECT date_trunc('day', ac.created_at) AS day,
                   ac.document_id AS documentId,
                   d.title AS title,
                   count(*) AS calls,
                   sum(ac.est_cost_inr) AS cost,
                   percentile_cont(0.95) WITHIN GROUP (ORDER BY ac.latency_ms) AS p95
            FROM api_call ac
            JOIN document d ON d.id = ac.document_id
            WHERE d.owner_id = :owner
              AND ac.created_at >= :from
              AND ac.created_at < :to
            GROUP BY day, ac.document_id, d.title
            ORDER BY day DESC, cost DESC
            """, nativeQuery = true)
    List<UsageSummaryRow> summarizeByOwnerAndDay(@Param("owner") String owner, @Param("from") OffsetDateTime from,
                                                  @Param("to") OffsetDateTime to);
}
