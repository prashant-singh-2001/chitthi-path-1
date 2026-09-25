package com.chitthi.progress;

import java.util.List;
import java.util.UUID;

/**
 * A full, self-contained view of a document's progress - every page's
 * current status, not just what changed. Sent whole on every SSE message so
 * a client that connects late, reconnects, or misses a delivery is always
 * looking at the truth rather than reconstructing it from a diff stream.
 */
public record ProgressSnapshot(UUID documentId, String status, List<PageProgress> pages) {
}
