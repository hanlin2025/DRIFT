package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Latest retained AIS fix for each vessel on a shipment. Positions are live fixes, not arrival estimates. A position is null when no report is retained for that vessel name.")
public record ShipmentTrackingResponse(
		@Schema(example = "MV Pacific Horizon")
		String motherVesselName,
		@Schema(description = "Latest retained AIS fix for the mother vessel. Null when no report is retained for that name.",
				types = { "object", "null" }, implementation = VesselPosition.class)
		VesselPosition motherVessel,
		@Schema(example = "MV Strait Runner")
		String feederVesselName,
		@Schema(description = "Latest retained AIS fix for the feeder vessel. Null when no report is retained for that name.",
				types = { "object", "null" }, implementation = VesselPosition.class)
		VesselPosition feederVessel) {
}
