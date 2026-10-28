package com.drift.backend.maritimecontext;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.drift.backend.shipment.ShipmentDetailResponse;

/** Explicit UI-only fixture until CDG-242--244 snapshots are ingested persistently. */
@Component
class SyntheticNoticeEvidenceAdapter implements MaritimeEvidenceSourceAdapter {
	private static final Instant RETRIEVED = Instant.parse("2026-10-27T00:05:00Z");

	@Override
	public List<MaritimeEvidence> forShipment(ShipmentDetailResponse shipment, Instant cutoff) {
		if (!"9351048".equals(shipment.motherVesselImo()) || RETRIEVED.isAfter(cutoff)) return List.of();
		return List.of(new MaritimeEvidence(
				"MPA_OFFICIAL_NOTICE", "SYNTHETIC_DEMONSTRATION", "DEMO-MPA-245-001",
				"Demonstration maritime operational notice", "MPA Port Marine Notice demonstration fixture", "https://www.mpa.gov.sg/",
				"Synthetic demonstration citation; no official notice text is bundled.", "2026-10-27T00:00:00+08:00",
				"2026-10-27T00:00:00+08:00", RETRIEVED.toString(), "SYNTHETIC_DEMONSTRATION", "", "", "",
				"UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", false, true, "UNVERIFIED_IMO_ONLY",
				"Demonstration only. It is not a verified disruption, route intersection, terminal event, or shipment association."));
	}
}
