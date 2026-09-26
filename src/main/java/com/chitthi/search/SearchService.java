package com.chitthi.search;

import org.springframework.stereotype.Service;

@Service
public class SearchService {

    private static final int MIN_QUERY_LENGTH = 2;
    private static final int MAX_QUERY_LENGTH = 200;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 50;

    private final SearchRepository repository;

    public SearchService(SearchRepository repository) {
        this.repository = repository;
    }

    public SearchResponse search(String owner, String q, String tag, Integer year, Integer limit) {
        validateQuery(q);
        int effectiveLimit = validateLimit(limit);

        long start = System.nanoTime();
        var hits = repository.search(owner, q, tag, year, effectiveLimit);
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        return new SearchResponse(hits, tookMs);
    }

    private void validateQuery(String q) {
        if (q == null || q.length() < MIN_QUERY_LENGTH || q.length() > MAX_QUERY_LENGTH) {
            throw new InvalidSearchQueryException(
                    "q must be between %d and %d characters".formatted(MIN_QUERY_LENGTH, MAX_QUERY_LENGTH));
        }
    }

    private int validateLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new InvalidSearchQueryException("limit must be between 1 and %d".formatted(MAX_LIMIT));
        }
        return limit;
    }
}
