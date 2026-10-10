package com.drift.backend.shipment.csvimport;

public class BulkImportForbiddenException extends RuntimeException {

	public BulkImportForbiddenException(String message) {
		super(message);
	}
}
