package com.drift.backend.shipment.csvimport;

public class ShipmentImportJobNotFoundException extends RuntimeException {

	public static final String MESSAGE = "Shipment import job not found";

	public ShipmentImportJobNotFoundException() {
		super(MESSAGE);
	}
}
