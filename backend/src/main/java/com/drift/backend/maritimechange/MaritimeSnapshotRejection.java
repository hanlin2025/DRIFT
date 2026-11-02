package com.drift.backend.maritimechange;

/** A snapshot that was not stored and did not emit a change. */
public record MaritimeSnapshotRejection(String sourceIdentity, String reason) {
}
