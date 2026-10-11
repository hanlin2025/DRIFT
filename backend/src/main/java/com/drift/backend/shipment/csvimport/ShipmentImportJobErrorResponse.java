package com.drift.backend.shipment.csvimport;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One row-level shipment import error.")
public record ShipmentImportJobErrorResponse(
		long rowNumber,
		String column,
		String code,
		String message) {

	static ShipmentImportJobErrorResponse from(ShipmentImportJobError error) {
		return new ShipmentImportJobErrorResponse(error.getRowNumber(), error.getColumnName(), error.getErrorCode(),
				error.getMessage());
	}
}
