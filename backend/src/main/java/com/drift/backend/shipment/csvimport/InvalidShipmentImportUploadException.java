package com.drift.backend.shipment.csvimport;

public class InvalidShipmentImportUploadException extends RuntimeException {

	public InvalidShipmentImportUploadException(String message) {
		super(message);
	}
}
