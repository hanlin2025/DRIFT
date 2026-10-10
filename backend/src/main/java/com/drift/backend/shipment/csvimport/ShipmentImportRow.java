package com.drift.backend.shipment.csvimport;

import java.time.OffsetDateTime;

/** A normalized, syntactically valid CSV row awaiting later import orchestration. */
public record ShipmentImportRow(
        long rowNumber,
        String shipmentReference,
        String origin,
        String destination,
        String transshipmentPort,
        String motherVessel,
        OffsetDateTime plannedMotherArrivalAt,
        String feederVessel,
        OffsetDateTime plannedFeederDepartureAt,
        String importerOrganisation) {
}
