package com.drift.backend.shipment;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Result of a CSV shipment import.")
public record ShipmentImportResponse(
		@Schema(example = "4")
		Long id,
		@Schema(example = "2")
		int imported,
		@Schema(example = "1")
		int failed,
		@Schema(example = "2 imported, 1 failed")
		String summary) {

	public static ShipmentImportResponse from(ShipmentImport shipmentImport) {
		return new ShipmentImportResponse(shipmentImport.getId(), shipmentImport.getImportedCount(),
				shipmentImport.getFailedCount(), summary(shipmentImport.getImportedCount(), shipmentImport.getFailedCount()));
	}

	public static String summary(int imported, int failed) {
		if (failed == 0) {
			return imported == 1 ? "1 shipment imported successfully" : imported + " shipments imported successfully";
		}
		return imported + " imported, " + failed + " failed";
	}
}
