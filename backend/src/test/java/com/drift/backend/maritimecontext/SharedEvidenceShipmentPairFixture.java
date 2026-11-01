package com.drift.backend.maritimecontext;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentStatus;

/**
 * Test-only selector that reuses existing synthetic MPA/NEA records for the same mother-vessel
 * visit across different feeder windows. The original end-to-end replay fixture pins a single
 * feeder departure as part of its scenario; this selector instead requires its accepted mother
 * arrival values and the same port/vessel identity, because neither source record describes a
 * feeder departure. This isolates downstream risk-context differentiation and does not change
 * production matching behavior.
 */
public final class SharedEvidenceShipmentPairFixture {
	private static final Instant CUTOFF = Instant.parse("2026-10-01T00:00:00Z");
	private static final OffsetDateTime EARLIER_ARRIVAL = OffsetDateTime.parse("2026-10-01T05:00:00+08:00");
	private static final OffsetDateTime REVISED_ARRIVAL = OffsetDateTime.parse("2026-10-01T06:00:00+08:00");

	private SharedEvidenceShipmentPairFixture() { }

	public static MaritimeContextService context() {
		List<MaritimeEvidence> sharedFacts = SyntheticMultiSourceMaritimeFixture.context().forShipment(baseline()).evidence().stream()
				.filter(item -> SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID.equals(item.evidenceId())
						|| SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID.equals(item.evidenceId()))
				.filter(MaritimeEvidence::evidenceSufficient)
				.filter(item -> !"INCOMPATIBLE".equals(item.compatibilityState()))
				.toList();
		if (sharedFacts.size() != 2) throw new IllegalStateException("The shared MPA/NEA fixture must contain both eligible source records.");

		return new MaritimeContextService(true, List.of((shipment, cutoff) ->
				!cutoff.isBefore(CUTOFF) && matchesSharedVisit(shipment) ? sharedFacts : List.of()), Clock.fixed(CUTOFF, ZoneOffset.UTC));
	}

	private static boolean matchesSharedVisit(ShipmentDetailResponse shipment) {
		// The mismatched-deadline integration case deliberately changes the current port after
		// recording a deadline. Keep the same fictional vessel observations in that test so it can
		// verify deadline applicability; these synthetic rows do not represent production evidence.
		boolean retainedDeadlineForOtherPort = shipment != null && shipment.transshipmentArrivalDeadline() != null
				&& !shipment.transshipmentArrivalDeadline().applicable();
		if (shipment == null || (!"Synthetic Multi-source Port".equals(shipment.transshipmentPort()) && !retainedDeadlineForOtherPort)
				|| !"Synthetic Multi-source Vessel".equals(shipment.motherVessel())
				|| !"9000002".equals(shipment.motherVesselImo())
				|| !"Synthetic Multi-source Feeder".equals(shipment.feederVessel())
				|| shipment.plannedMotherArrivalAt() == null || shipment.plannedFeederDepartureAt() == null) return false;
		Instant arrival = shipment.plannedMotherArrivalAt().toInstant();
		return (arrival.equals(EARLIER_ARRIVAL.toInstant()) || arrival.equals(REVISED_ARRIVAL.toInstant()))
				&& shipment.plannedFeederDepartureAt().toInstant().isAfter(arrival);
	}

	private static ShipmentDetailResponse baseline() {
		return new ShipmentDetailResponse(1L, 1L, "CDG262-FIXTURE", "Synthetic origin", "Synthetic destination",
				"Synthetic Multi-source Port", "Synthetic Multi-source Vessel", EARLIER_ARRIVAL, "9000002", null, null,
				"Synthetic Multi-source Feeder", OffsetDateTime.parse("2026-10-01T14:00:00+08:00"), null, null, null,
				Instant.EPOCH, 0L, null, null, null, null, null, null, null, null, null, null, null, null, null, null,null, null,  null,
				ShipmentStatus.ACTIVE);
	}
}
