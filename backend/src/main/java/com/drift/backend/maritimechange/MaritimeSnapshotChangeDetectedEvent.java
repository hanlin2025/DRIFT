package com.drift.backend.maritimechange;

/** Published only when the detector persists a new source fingerprint. */
public record MaritimeSnapshotChangeDetectedEvent(MaritimeSnapshotChange change) { }
