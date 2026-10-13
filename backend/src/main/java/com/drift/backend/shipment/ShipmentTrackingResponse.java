package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Latest retained AIS fix for each vessel on a shipment. Positions are live fixes, not arrival estimates. A position is null when no report is retained for that vessel name. Each name warning belongs to the position in this response.")
public record ShipmentTrackingResponse(
		@Schema(example = "MV Pacific Horizon")
		String motherVesselName,
		@Schema(description = "Latest retained AIS fix for the mother vessel. Null when no report is retained for that name.",
				types = { "object", "null" }, implementation = VesselPosition.class)
		VesselPosition motherVessel,
		@Schema(description = "Set when the mother-vessel position in this response names a different vessel from the stored mother vessel. Null when that position is missing, unnamed, or the names match after trimming and case folding. The MV / M/V prefix is kept.", nullable = true)
		String motherVesselNameWarning,
		@Schema(example = "MV Strait Runner")
		String feederVesselName,
		@Schema(description = "Latest retained AIS fix for the feeder vessel. Null when no report is retained for that name.",
				types = { "object", "null" }, implementation = VesselPosition.class)
		VesselPosition feederVessel,
		@Schema(description = "Set when the feeder-vessel position in this response names a different vessel from the stored feeder vessel. Null when that position is missing, unnamed, or the names match after trimming and case folding. The MV / M/V prefix is kept.", nullable = true)
		String feederVesselNameWarning) {
}
