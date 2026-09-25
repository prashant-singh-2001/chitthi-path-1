package com.chitthi.progress;

import java.util.UUID;

/**
 * Raised once per state-changing transaction that touches a document or any
 * of its pages, inside that same transaction. {@code ProgressBroadcaster}
 * only acts on this after commit, so a rolled-back write never announces a
 * status change that didn't actually happen.
 *
 * <p>This carries only the document id, not the change itself - a listener
 * that wants to tell subscribers what changed always reloads the current
 * state fresh (see {@code ProgressSnapshotService}), so a client that
 * connects between two commits or misses a delivery still ends up correct.
 */
public record DocumentProgressEvent(UUID documentId) {
}
