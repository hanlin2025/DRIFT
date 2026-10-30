package com.drift.backend.cdg201;

import java.time.Instant;
import java.time.OffsetDateTime;

import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentStatus;

final class TestShipments {
	private TestShipments() { }
	static ShipmentDetailResponse kotaLaris() { return detail("9351048"); }
	static ShipmentDetailResponse otherVessel() { return detail("9387421"); }
	private static ShipmentDetailResponse detail(String imo) {
		return new ShipmentDetailResponse(1L, 1L, "CONTRACT-DEMO", "A", "B", "Singapore", "KOTA LARIS",
				OffsetDateTime.parse("2026-10-28T23:00:00+08:00"), imo, null, null, "SIMULATED FEEDER",
				OffsetDateTime.parse("2026-10-29T12:00:00+08:00"), null, null, null, Instant.EPOCH, 0L,
				null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				ShipmentStatus.ACTIVE, null);
	}
}
