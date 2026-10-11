package com.drift.backend.shipment.csvimport;

public class BulkImportForbiddenException extends RuntimeException {

	public static final String ACTIVE_COMPANY = "Bulk import requires an active company";
	public static final String ADMIN_DEFERRED = "ADMIN bulk-import access is deferred until company type is defined";
	public static final String ROLE = "Your role is not authorized to import shipments in bulk";

	public BulkImportForbiddenException(String message) {
		super(message);
	}
}
