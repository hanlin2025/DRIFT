package com.drift.backend.ais.operation;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Operational state derived from the latest AIS navigational status.")
public record OperationalState(
		@Schema(description = "Readable operational state. A missing or undefined navigational status is Unknown.", example = "At anchor")
		String label,
		@Schema(description = "Operational state with context relative to the transshipment port when the vessel position and port are known.", example = "At anchor near Singapore")
		String description,
		@Schema(description = "True when a stopped status, such as Moored, is reported while speed is above 1 knot.")
		boolean unreliable,
		@Schema(description = "When DRIFT stored the AIS report used to derive this state. Null when no observation was retained.", nullable = true)
		Instant ingestedAt) {

	public OperationalState {
		if (label == null || label.isBlank()) {
			throw new IllegalArgumentException("Operational state label is required");
		}
		if (description == null || description.isBlank()) {
			throw new IllegalArgumentException("Operational state description is required");
		}
		if (!description.startsWith(label)) {
			throw new IllegalArgumentException("Operational state description must start with its label");
		}
	}
}
