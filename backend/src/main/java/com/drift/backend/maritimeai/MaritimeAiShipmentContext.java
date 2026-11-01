package com.drift.backend.maritimeai;

import java.time.OffsetDateTime;

/**
 * Minimal deterministic planning context for interpreting evidence; deliberately excludes company,
 * customer, cargo, and reference. It is not retrieved maritime evidence and therefore has no
 * evidence ID that a generated claim may cite.
 */
public record MaritimeAiShipmentContext(String transshipmentPort, Vessel motherVessel, Vessel feederVessel,
		MaritimeAiConnectionRiskContext connectionRisk, MaritimeAiBusinessContext businessContext) {
	/** Existing callers without a risk snapshot remain valid; unknown context is not synthesized. */
	public MaritimeAiShipmentContext(String transshipmentPort, Vessel motherVessel, Vessel feederVessel) {
		this(transshipmentPort, motherVessel, feederVessel, null, null);
	}
	/** Existing risk-aware callers remain valid while business context stays unknown. */
	public MaritimeAiShipmentContext(String transshipmentPort, Vessel motherVessel, Vessel feederVessel,
			MaritimeAiConnectionRiskContext connectionRisk) {
		this(transshipmentPort, motherVessel, feederVessel, connectionRisk, null);
	}
	public record Vessel(String role, String name, String imo, String plannedMovementType, OffsetDateTime plannedTime) { }
}
