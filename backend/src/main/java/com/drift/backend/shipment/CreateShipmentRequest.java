package com.drift.backend.shipment;

import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "The shipment and its planned transshipment itinerary.")
public record CreateShipmentRequest(
		@Schema(description = "Company-scoped shipment reference.", example = "HBL-2026-001", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "Shipment reference is required")
		@Size(max = 100, message = "Shipment reference must be at most 100 characters")
		String shipmentReference,
		@Schema(description = "Shipment origin.", example = "Shanghai, CN", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "Origin is required")
		@Size(max = 200, message = "Origin must be at most 200 characters")
		String origin,
		@Schema(description = "Shipment destination.", example = "Jakarta, ID", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "Destination is required")
		@Size(max = 200, message = "Destination must be at most 200 characters")
		String destination,
		@Schema(description = "Port where the shipment transfers from the mother vessel to the feeder vessel.", example = "Singapore", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "Transshipment port is required")
		@Size(max = 200, message = "Transshipment port must be at most 200 characters")
		String transshipmentPort,
		@Schema(description = "Vessel carrying the shipment to the transshipment port.", example = "MV Pacific Horizon", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "Mother vessel is required")
		@Size(max = 200, message = "Mother vessel must be at most 200 characters")
		String motherVessel,
		@Schema(description = "Planned arrival of the mother vessel at the transshipment port, including a UTC offset.", example = "2026-10-15T08:00:00+08:00", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "Planned mother-vessel arrival is required")
		OffsetDateTime plannedMotherArrivalAt,
		@Schema(description = "Vessel carrying the shipment from the transshipment port.", example = "MV Strait Runner", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "Feeder vessel is required")
		@Size(max = 200, message = "Feeder vessel must be at most 200 characters")
		String feederVessel,
		@Schema(description = "Planned feeder-vessel departure from the transshipment port, including a UTC offset. It must be after the mother-vessel arrival.", example = "2026-10-16T12:00:00+08:00", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "Planned feeder-vessel departure is required")
		OffsetDateTime plannedFeederDepartureAt) implements ShipmentDetailsRequest {
}
