package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Latest retained AIS fix for each vessel on a shipment. Positions are live fixes, not arrival estimates. A position is null when no report is retained for that vessel name.")
public record ShipmentTrackingResponse(
		@Schema(example = "MV Pacific Horizon")
		String motherVesselName,
		VesselPosition motherVessel,
		@Schema(example = "MV Strait Runner")
		String feederVesselName,
		VesselPosition feederVessel) {
}
