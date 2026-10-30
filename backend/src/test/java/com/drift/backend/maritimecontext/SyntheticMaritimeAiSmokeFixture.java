package com.drift.backend.maritimecontext;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Test-scope-only evidence for CDG-247 protocol and persistence smoke tests. It deliberately
 * neither inspects shipment fields nor references CDG-201 replay data, collectors, or notices.
 */
public final class SyntheticMaritimeAiSmokeFixture {
	private SyntheticMaritimeAiSmokeFixture() { }

	public static MaritimeContextService context() {
		return new MaritimeContextService(true, List.of((shipment, cutoff) -> matchesSyntheticShipment(shipment) ? List.of(evidence()) : List.of()),
				Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
	}

	private static boolean matchesSyntheticShipment(com.drift.backend.shipment.ShipmentDetailResponse shipment) {
		return "Synthetic port".equals(shipment.transshipmentPort())
				&& "Synthetic vessel".equals(shipment.motherVessel())
				&& "9000001".equals(shipment.motherVesselImo())
				&& shipment.plannedMotherArrivalAt() != null
				&& OffsetDateTime.parse("2026-10-01T08:00:00+08:00").toInstant().equals(shipment.plannedMotherArrivalAt().toInstant())
				&& "Synthetic feeder".equals(shipment.feederVessel())
				&& shipment.plannedFeederDepartureAt() != null
				&& OffsetDateTime.parse("2026-10-01T16:00:00+08:00").toInstant().equals(shipment.plannedFeederDepartureAt().toInstant());
	}

	public static MaritimeEvidence evidence() {
		return new MaritimeEvidence(
				"SYNTHETIC_MARITIME_EVENT", "SYNTHETIC_DEMONSTRATION", "synthetic-smoke-001",
				"Synthetic port-planning fixture", "CDG-247 test fixture", "https://example.invalid/cdg247/synthetic",
				"Independently authored synthetic evidence; not an official notice or vessel event.",
				"2026-10-01T07:00:00+08:00", "2026-10-01T07:00:00+08:00", "2026-09-30T23:00:00Z",
				"FRESH", "Synthetic prior schedule", "Synthetic revised schedule", "+30 minutes",
				"COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true,
				"VERIFIED", "Verified only within the internally constructed CDG-247 fictional smoke fixture: its fictional shipment, vessel, schedule, and evidence IDs are deliberately matched. It establishes no real shipment, vessel, cargo, ETA, or operational fact.");
	}
}
