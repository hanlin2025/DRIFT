package com.drift.backend.shipment.csvimport;

/** A file-level failure that prevents any rows from being considered for import. */
public class ShipmentImportFileException extends RuntimeException {

    public ShipmentImportFileException(String message) {
        super(message);
    }

    public ShipmentImportFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
