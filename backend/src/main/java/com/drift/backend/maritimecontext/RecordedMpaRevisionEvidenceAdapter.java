package com.drift.backend.maritimecontext;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.drift.backend.shipment.ShipmentDetailResponse;

/**
 * Reproducible CDG-201 replay input.  It is intentionally not a connection to
 * a local collector directory or to a live MPA API.
 */
@Component
class RecordedMpaRevisionEvidenceAdapter implements MaritimeEvidenceSourceAdapter {
	private static final Instant RETRIEVED = Instant.parse("2026-10-26T15:46:03.213897Z");

	@Override
	public List<MaritimeEvidence> forShipment(ShipmentDetailResponse shipment, Instant cutoff) {
		if (!"9351048".equals(shipment.motherVesselImo()) || RETRIEVED.isAfter(cutoff)) return List.of();
		return List.of(new MaritimeEvidence(
				"MPA_ETA_REVISION", "RECORDED_REPLAY", "cdg201-kota-laris-20261026-154603",
				"Recorded MPA expected-arrival revision", "MPA OCEANS-X Vessels Due to Arrive",
				"", "Manifest due_to_arrive_2026-10-29_20261026T154603Z.manifest.json; raw response due_to_arrive_2026-10-29_20261026T154603Z.json",
				"2026-10-29T02:00:00+08:00", "2026-10-26T15:46:03.213897Z", RETRIEVED.toString(),
				"RECORDED_REPLAY", "2026-10-28 23:00:00", "2026-10-29 02:00:00", "+180 minutes",
				"UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", true, true, "UNVERIFIED_IMO_ONLY",
				"Recorded replay only. MPA source-event strings use Asia/Singapore; this does not verify a shipment booking, port call, berth, feeder connection, or actual arrival."));
	}
}
