package com.drift.backend.cdg201;

import org.springframework.stereotype.Service;

import com.drift.backend.shipment.ShipmentDetailResponse;

/**
 * Fixture-backed replay seam for CDG-201. It performs no persistence, network
 * I/O, schedule updates, risk calculation, or mutation of shipment data.
 */
@Service
public class Cdg201ArrivalEvidenceService {

	private static final String KOTA_LARIS_IMO = "9351048";

	public Cdg201ArrivalEvidence forShipment(ShipmentDetailResponse shipment) {
		if (shipment == null || !KOTA_LARIS_IMO.equals(shipment.motherVesselImo())) {
			return null;
		}
		return new Cdg201ArrivalEvidence(
				"cdg201.drift-arrival-evidence.v1", "RECORDED_EVIDENCE_REPLAY",
				"KOTA LARIS", KOTA_LARIS_IMO,
				"2026-10-28 23:00:00", "2026-10-29 02:00:00", 180,
				"2026-10-26T15:10:22.248554+00:00", "2026-10-26T15:46:03.213897+00:00",
				"mpa-20261026T151021387321Z", "mpa-20261026T154602315050Z",
				"due_to_arrive_2026-10-28_20261026T151022Z.manifest.json",
				"due_to_arrive_2026-10-28_20261026T151022Z.json",
				"due_to_arrive_2026-10-29_20261026T154603Z.manifest.json",
				"due_to_arrive_2026-10-29_20261026T154603Z.json",
				"conservative_context_time_continuity", "NONE_FOR_REPLAY_EVENT",
				"MPA expected-arrival source timestamp is retained verbatim; its timezone offset is undocumented.",
				"SEA", "PEBGA", "UNKNOWN_MPA_CODE_NOT_MAPPED_TO_DRIFT_PORT",
				"UNVERIFIED_IMO_ONLY", "Recorded MPA revision replay. It does not verify a booked shipment, port call, feeder connection, or actual arrival.",
				new Cdg201SimulatedConnectionScenario("SIMULATED_CONNECTION_ASSUMPTIONS", 780, 600, 180,
						"MEDIUM", "HIGH", "Synthetic feeder timing and transfer buffer only; not a production risk result or missed-connection finding."));
	}
}
