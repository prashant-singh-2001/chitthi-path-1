package com.chitthi.search;

import java.util.List;
import java.util.UUID;

public record SearchHit(UUID documentId, String title, Integer year, List<String> tags, int pageNo,
                         String snippet, String matchedIn, double rank) {
}
