package com.drift.backend.maritimechange;

import java.util.List;

/** The changes emitted by one acceptance, and the snapshots that were refused. */
public record MaritimeSnapshotChangeBatch(
		List<MaritimeSnapshotChange> changes,
		List<MaritimeSnapshotRejection> rejections) {
}
