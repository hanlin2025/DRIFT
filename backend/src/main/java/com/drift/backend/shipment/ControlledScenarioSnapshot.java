package com.drift.backend.shipment;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.maritimecontext.MaritimeContextResponse;

/** One immutable stage read with one identity and cutoff shared by risk, impact, evidence, and map playback. */
public record ControlledScenarioSnapshot(
		UUID snapshotId,
		UUID epochId,
		long revision,
		ControlledScenarioStage stage,
		int stageOrder,
		int stageCount,
		String fixtureStageId,
		Instant simulationCutoffUtc,
		Instant mapPlaybackCutoffUtc,
		List<ShipmentRead> shipments,
		List<AisObservationRead> aisObservations,
		MaritimeContextResponse maritimeContext) {

	public ControlledScenarioSnapshot {
		shipments = List.copyOf(shipments);
		aisObservations = List.copyOf(aisObservations);
	}

	/** Calculated once at stage commit by the existing estimator, risk, and deadline comparison services. */
	public record ShipmentRead(
			String scenarioShipmentKey,
			Long sourceShipmentId,
			String scenarioShipmentReference,
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
