package com.drift.backend.maritimecontext;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Test-scope-only evidence for CDG-247 protocol and persistence smoke tests. It deliberately
 * neither inspects shipment fields nor references CDG-201 replay data, collectors, or notices.
 */
public final class SyntheticMaritimeAiSmokeFixture {
	private SyntheticMaritimeAiSmokeFixture() { }

	public static MaritimeContextService context() {
		return new MaritimeContextService(true, List.of((shipment, cutoff) -> List.of(evidence())),
				Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
	}

	public static MaritimeEvidence evidence() {
		return new MaritimeEvidence(
				"SYNTHETIC_MARITIME_EVENT", "SYNTHETIC_SMOKE_TEST", "synthetic-smoke-001",
				"Synthetic port-planning fixture", "CDG-247 test fixture", "https://example.invalid/cdg247/synthetic",
				"Independently authored synthetic evidence; not an official notice or vessel event.",
				"2026-10-01T07:00:00+08:00", "2026-10-01T07:00:00+08:00", "2026-09-30T23:00:00Z",
				"FRESH", "Synthetic prior schedule", "Synthetic revised schedule", "+30 minutes",
				"COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true,
				"SYNTHETIC_TEST_FIXTURE", "Synthetic-only smoke-test evidence. It establishes no real shipment, vessel, ETA, or operational fact.");
	}
}
