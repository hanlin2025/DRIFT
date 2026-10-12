package com.drift.backend.shipment;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

public record UpdateShipmentRequest(
		@NotBlank(message = "Shipment reference is required")
		@Size(max = 100, message = "Shipment reference must be at most 100 characters")
		String shipmentReference,
		@NotBlank(message = "Origin is required")
		@Size(max = 200, message = "Origin must be at most 200 characters")
		String origin,
		@NotBlank(message = "Destination is required")
		@Size(max = 200, message = "Destination must be at most 200 characters")
		String destination,
		@NotBlank(message = "Transshipment port is required")
		@Size(max = 200, message = "Transshipment port must be at most 200 characters")
		String transshipmentPort,
		@NotBlank(message = "Mother vessel is required")
		@Size(max = 200, message = "Mother vessel must be at most 200 characters")
		String motherVessel,
		@NotNull(message = "Planned mother-vessel arrival is required")
		OffsetDateTime plannedMotherArrivalAt,
		@NotBlank(message = "Feeder vessel is required")
		@Size(max = 200, message = "Feeder vessel must be at most 200 characters")
		String feederVessel,
		@NotNull(message = "Planned feeder-vessel departure is required")
		OffsetDateTime plannedFeederDepartureAt,
		@Schema(description = "Optional 7-digit IMO number of the mother vessel. Omit the key to leave the stored value unchanged. Null or blank clears it.", example = "9387421", nullable = true)
		String motherVesselImo,
		@Schema(description = "Optional 9-digit MMSI of the mother vessel. Omit the key to leave the stored value unchanged. Null or blank clears it.", example = "563123456", nullable = true)
		String motherVesselMmsi,
		@Schema(description = "Optional operator of the mother vessel. Omit the key to leave the stored value unchanged. Null or blank clears it.", example = "Harbourline", nullable = true)
		@Size(max = 200, message = "Mother vessel operator must be at most 200 characters.")
		String motherVesselOperator,
		@Schema(description = "Optional 7-digit IMO number of the feeder vessel. Omit the key to leave the stored value unchanged. Null or blank clears it.", example = "9411882", nullable = true)
		String feederVesselImo,
		@Schema(description = "Optional 9-digit MMSI of the feeder vessel. Omit the key to leave the stored value unchanged. Null or blank clears it.", example = "565987654", nullable = true)
		String feederVesselMmsi,
		@Schema(description = "Optional operator of the feeder vessel. Omit the key to leave the stored value unchanged. Null or blank clears it.", example = "Strait Feeders", nullable = true)
		@Size(max = 200, message = "Feeder vessel operator must be at most 200 characters.")
		String feederVesselOperator,
		@NotNull(message = "Version is required")
		@PositiveOrZero(message = "Version must not be negative")
		@Schema(description = "Optimistic-lock version returned when the shipment was last read. A stale value returns 409 Conflict.", example = "0")
		Long version) implements ShipmentDetailsRequest {
}
