package com.drift.backend.maritimeingest;

import java.time.Instant;
import java.util.List;

import com.drift.backend.maritimechange.MaritimeSnapshot;

/** One live collector. Each run returns normalized snapshots for the change detector. */
interface MaritimeSnapshotSource {

	String name();

	boolean enabled();

	List<MaritimeSnapshot> fetch(Instant retrievedAt);
}
