package com.drift.backend.shipment.csvimport;

/** A row-level issue that can later be returned in an import summary or error report. */
public record ShipmentImportRowError(long rowNumber, String column, String code, String message) {
}
