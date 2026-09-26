package com.chitthi.search;

import java.util.List;

public record SearchResponse(List<SearchHit> hits, long tookMs) {
}
