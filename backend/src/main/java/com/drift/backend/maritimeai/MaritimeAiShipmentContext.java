package com.drift.backend.maritimeai;

import java.time.OffsetDateTime;

/** Minimal planning context for interpreting evidence; deliberately excludes company, customer, cargo, and reference. */
public record MaritimeAiShipmentContext(String transshipmentPort, Vessel motherVessel, Vessel feederVessel) {
	public record Vessel(String name, String imo, OffsetDateTime plannedTime) { }
}
