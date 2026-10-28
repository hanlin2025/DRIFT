package com.drift.backend.maritimecontext;

import java.time.Instant;
import java.util.List;

import com.drift.backend.shipment.ShipmentDetailResponse;

/** A future persistent-snapshot adapter can implement this without changing the API contract. */
interface MaritimeEvidenceSourceAdapter {
	List<MaritimeEvidence> forShipment(ShipmentDetailResponse shipment, Instant assessmentCutoffUtc);
}
