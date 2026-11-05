package com.drift.backend.maritimecontext;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.drift.backend.shipment.ShipmentDetailResponse;

/**
 * Explicit local/staging-only controlled replay. It is disabled by default and matches one
 * deliberately fictional shipment shape; it never reads collector data or changes shipment state.
 */
@Component
class ControlledScenarioReplayEvidenceAdapter implements MaritimeEvidenceSourceAdapter {
	private final boolean enabled;
	private final Stage stage;

	ControlledScenarioReplayEvidenceAdapter(
			@Value("${app.maritime-context.controlled-replay-enabled:false}") boolean enabled,
			@Value("${app.maritime-context.controlled-replay-stage:BASELINE}") String configuredStage) {
		this.enabled = enabled;
		this.stage = Stage.parse(configuredStage);
	}

	@Override public List<MaritimeEvidence> forShipment(ShipmentDetailResponse shipment, Instant cutoff) {
		if (!enabled || !matches(shipment)) return List.of();
		return switch (stage) {
			case BASELINE -> List.of();
			case ARRIVAL_REVISION -> List.of(mpa());
			case MARITIME_CONTEXT -> List.of(mpa(), notice(), weather(), incompatibleNotice());
		};
	}

	private static boolean matches(ShipmentDetailResponse shipment) {
		return shipment.shipmentReference() != null && shipment.shipmentReference().startsWith("DEMO-CONTROLLED-")
				&& "Synthetic Multi-source Port".equals(shipment.transshipmentPort())
				&& "Synthetic Multi-source Vessel".equals(shipment.motherVessel())
				&& "9000002".equals(shipment.motherVesselImo())
				&& shipment.plannedMotherArrivalAt() != null
				&& shipment.plannedMotherArrivalAt().toInstant().equals(Instant.parse("2026-09-30T21:00:00Z"));
	}

	private static MaritimeEvidence mpa() { return evidence("MPA_ETA_REVISION", "demo-synthetic-mpa-schedule-001",
			"Synthetic expected-arrival schedule revision", "SYNTHETIC-MPA-REVISION-001", "Synthetic MPA-style revision; fictional source and retrieval record.",
			null, null, "2026-09-30T21:00:00Z", "2026-10-01T05:00:00+08:00", "2026-10-01T06:00:00+08:00", "+60 minutes",
			"COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, "MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION"); }
	private static MaritimeEvidence notice() { return evidence("MPA_OFFICIAL_NOTICE", "demo-synthetic-notice-001",
			"Synthetic port-area maintenance notice", "SYNTHETIC-PMN-001", "Synthetic Notice 001, page 1: fictional maintenance at Synthetic Port Area, effective 06:00-12:00 SGT.",
			null, null, "2026-09-30T21:30:00Z", null, "Effective 2026-10-01 06:00-12:00 SGT.", null,
			"COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, null, null, "PORT_CONTEXT"); }
	private static MaritimeEvidence weather() { return evidence("NEA_WEATHER", "demo-synthetic-nea-rainfall-001",
			"Synthetic regional rainfall observation", "SYNTHETIC-NEA-RAINFALL-001", "Synthetic NEA-style regional weather context only.",
			null, "2026-10-01T05:00:00+08:00", "2026-09-30T22:00:00Z", null, "Synthetic rainfall: 12.0 mm; area: Synthetic Port Area.", null,
			"UNKNOWN", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, null, null, "REGIONAL_CONTEXT"); }
	private static MaritimeEvidence incompatibleNotice() { return evidence("MPA_OFFICIAL_NOTICE", "demo-synthetic-notice-irrelevant-001",
			"Synthetic notice for a different fictional area", "SYNTHETIC-PMN-IRRELEVANT-001", "Synthetic Notice 002, page 1: fictional work in a different area.",
			null, null, "2026-09-30T22:30:00Z", null, "Area: Different Synthetic Area.", null,
			"INCOMPATIBLE", "INCOMPATIBLE", "COMPATIBLE", "INCOMPATIBLE", true, null, null, "PORT_CONTEXT"); }

	private static MaritimeEvidence evidence(String type, String id, String title, String source, String citation, String eventAt,
			String observedAt, String retrievedAt, String previous, String current, String magnitude, String activity, String location, String time,
			String compatibility, boolean sufficient, String role, String observationType, String scope) {
		return new MaritimeEvidence(type, "SYNTHETIC_DEMONSTRATION", id, title, source, "https://example.invalid/controlled-replay", citation,
			eventAt, observedAt, retrievedAt, "FRESH", previous, current, magnitude, activity, location, time, compatibility,
				sufficient, true, "VERIFIED", "Controlled fictional scenario only; it proves no real vessel, notice, weather condition, cargo, delay, or connection.",
				"Synthetic Multi-source Vessel", role, observationType, scope);
	}

	enum Stage { BASELINE, ARRIVAL_REVISION, MARITIME_CONTEXT;
		static Stage parse(String value) { try { return valueOf(value == null ? "BASELINE" : value.trim().toUpperCase()); }
			catch (IllegalArgumentException ex) { throw new IllegalStateException("Unsupported controlled replay stage"); } }
	}
}
