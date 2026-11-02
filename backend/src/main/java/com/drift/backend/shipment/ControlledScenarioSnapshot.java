package com.drift.backend.shipment;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.maritimecontext.MaritimeContextResponse;

/** One immutable stage read with one identity and cutoff shared by risk, impact, evidence, and map playback. */
public record ControlledScenarioSnapshot(
		UUID sessionId,
		String scenarioId,
		String contractVersion,
		ControlledScenarioStage stage,
		int stageOrder,
		String fixtureStageId,
		String snapshotId,
		Instant simulationCutoffUtc,
		Instant mapPlaybackCutoffUtc,
		boolean nextAvailable,
		boolean resetAvailable,
		List<ShipmentRead> shipments,
		List<AisObservationRead> aisObservations,
		MaritimeContextResponse maritimeContext) {

	public ControlledScenarioSnapshot {
		shipments = List.copyOf(shipments);
		aisObservations = List.copyOf(aisObservations);
	}

	/** Computed on read by the same estimator, connection-window risk, and deadline comparison as shipment detail. */
	public record ShipmentRead(
			String shipmentReference,
			String transshipmentPort,
			OffsetDateTime plannedMotherArrivalAt,
			OffsetDateTime plannedFeederDepartureAt,
			ConnectionWindowCalculation plannedConnectionWindow,
			RemainingConnectionWindow remainingConnectionWindow,
			ArrivalEstimate arrivalEstimate,
			ShipmentRisk risk,
			DelayComparison businessImpact) { }

	/** Original fixture report/receipt times and source mode remain separate from the simulation cutoff. */
	public record AisObservationRead(
			String vesselRole,
			Instant observedAtUtc,
			Instant receivedAtUtc,
			String sourceMode,
			AisPosition position) { }
}
