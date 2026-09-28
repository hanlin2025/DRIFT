package com.drift.backend.shipment;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateShipmentRequest(
		@NotBlank(message = "Shipment reference is required")
		@Size(max = 100, message = "Shipment reference must be at most 100 characters")
		String shipmentReference,
		@NotBlank(message = "Origin is required")
		@Size(max = 200, message = "Origin must be at most 200 characters")
		String origin,
		@NotBlank(message = "Destination is required")
		@Size(max = 200, message = "Destination must be at most 200 characters")
		String destination,
		@NotBlank(message = "Mother vessel is required")
		@Size(max = 200, message = "Mother vessel must be at most 200 characters")
		String motherVessel,
		@NotNull(message = "Planned mother-vessel arrival is required")
		OffsetDateTime plannedMotherArrivalAt,
		@NotBlank(message = "Feeder vessel is required")
		@Size(max = 200, message = "Feeder vessel must be at most 200 characters")
		String feederVessel,
		@NotNull(message = "Planned feeder-vessel departure is required")
		OffsetDateTime plannedFeederDepartureAt) {
}
