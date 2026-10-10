package com.drift.backend.shipment.csvimport;

import java.util.List;

/** Canonical header contract shared by the CSV parser and downloadable template. */
public final class ShipmentImportCsvContract {

    public static final String TRACKING_BL_NUMBER = "Tracking/BL No.";
    public static final String ORIGIN_PORT = "Origin Port";
    public static final String DESTINATION_PORT = "Destination Port";
    public static final String TRANSSHIPMENT_PORT = "Transshipment Port";
    public static final String MOTHER_VESSEL = "Mother Vessel";
    public static final String PLANNED_MOTHER_ARRIVAL = "Planned Mother Arrival";
    public static final String FEEDER_VESSEL = "Feeder Vessel";
    public static final String PLANNED_FEEDER_DEPARTURE = "Planned Feeder Departure";
    public static final String IMPORTER_ORGANISATION = "Importer Organisation";

    public static final List<String> HEADERS = List.of(
            TRACKING_BL_NUMBER,
            ORIGIN_PORT,
            DESTINATION_PORT,
            TRANSSHIPMENT_PORT,
            MOTHER_VESSEL,
            PLANNED_MOTHER_ARRIVAL,
            FEEDER_VESSEL,
            PLANNED_FEEDER_DEPARTURE,
            IMPORTER_ORGANISATION);

    private ShipmentImportCsvContract() {
    }
}
