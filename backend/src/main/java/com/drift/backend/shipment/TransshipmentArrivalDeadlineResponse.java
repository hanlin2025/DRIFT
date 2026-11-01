package com.drift.backend.shipment;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A linked importer's deadline for mother-vessel arrival at one transshipment port.")
public record TransshipmentArrivalDeadlineResponse(
		@Schema(description = "Deadline instant in UTC.", example = "2026-10-28T16:00:00Z")
		OffsetDateTime deadline,
		@Schema(description = "Port text recorded when the importer confirmed this deadline.", example = "Singapore")
		String transshipmentPort,
		@Schema(description = "True when the recorded port is the shipment's current transshipment port.")
		boolean applicable) {

	static TransshipmentArrivalDeadlineResponse from(Shipment shipment, ShipmentImporterConstraint constraint) {
		String recordedPort = constraint.getTransshipmentPort();
		boolean applicable = shipment.getTransshipmentPort().strip().equals(recordedPort);
		return new TransshipmentArrivalDeadlineResponse(
				constraint.getDeadline().toInstant().atOffset(ZoneOffset.UTC), recordedPort, applicable);
	}
}
