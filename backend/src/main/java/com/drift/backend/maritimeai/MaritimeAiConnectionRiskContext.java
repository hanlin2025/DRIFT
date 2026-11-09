package com.drift.backend.maritimeai;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import com.drift.backend.shipment.AssessmentExplanation;
import com.drift.backend.shipment.ConnectionWindow;
import com.drift.backend.shipment.RemainingConnectionWindow;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentRisk;
import com.drift.backend.shipment.ShipmentRiskContribution;
import com.drift.backend.shipment.ShipmentRiskContributions;
import com.drift.backend.shipment.ControlledScenarioSnapshot;

/** Minimized snapshot of the authorized backend risk result and the inputs that produced it.
 * It is planning context, never retrieved maritime evidence or an instruction to recalculate risk. */
public record MaritimeAiConnectionRiskContext(boolean assessed, String unavailableReason,
		ShipmentRisk.Level riskLevel, RiskBasis riskBasis, PlannedWindow plannedWindow,
		RemainingWindow remainingWindow, ArrivalEstimateFact arrivalEstimate,
		List<String> assumptions) {
	public enum RiskBasis { PLANNED_SHIPMENT_SCHEDULE, AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE }
	public enum WindowState { AVAILABLE, INVALID, INCOMPLETE }
	public enum EstimateState { AVAILABLE, STALE, UNAVAILABLE, UNKNOWN }

	public record PlannedWindow(WindowState state, Long durationSeconds) { }
	public record RemainingWindow(String status, Long remainingSeconds, Long missedBySeconds) { }
	public record ArrivalEstimateFact(EstimateState state, OffsetDateTime estimatedArrivalAt,
		Instant positionReceivedAt, String unavailableReason) { }

	static MaritimeAiConnectionRiskContext from(ShipmentDetailResponse shipment) {
		if (shipment == null || shipment.riskContributions() == null) return null;
		ShipmentRiskContributions contributions = shipment.riskContributions();
		ConnectionWindow planned = shipment.connectionWindow();
		WindowState plannedState = planned != null ? WindowState.AVAILABLE
				: contributions.contributions().stream().anyMatch(item ->
						item.code() == ShipmentRiskContribution.Code.PLANNED_WINDOW)
						? WindowState.INVALID
						: contributions.assessed() ? WindowState.INVALID : WindowState.INCOMPLETE;
		RemainingConnectionWindow remaining = shipment.remainingConnectionWindow();
		RiskBasis basis = !contributions.assessed() ? null : remaining != null
				? RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE : RiskBasis.PLANNED_SHIPMENT_SCHEDULE;
		return new MaritimeAiConnectionRiskContext(contributions.assessed(), contributions.notAssessed(),
				shipment.risk() == null ? null : shipment.risk().level(), basis,
				new PlannedWindow(plannedState, planned == null ? null : planned.totalSeconds()),
				remaining == null ? null : new RemainingWindow(remaining.status().name(),
						remaining.totalSeconds(), remaining.missedBySeconds()),
				arrivalEstimate(shipment.arrivalComparison()),
				assumptions(shipment.assessmentExplanation()));
	}

	/** Projects only backend-calculated scenario values; it does not recalculate risk or windows. */
	static MaritimeAiConnectionRiskContext fromScenario(ControlledScenarioSnapshot.ShipmentRead shipment) {
		if (shipment == null) return null;
		var planned = shipment.plannedConnectionWindow();
		var remaining = shipment.remainingConnectionWindow();
		var estimate = shipment.arrivalEstimate();
		boolean assessed = shipment.risk() != null;
		RiskBasis basis = !assessed ? null : remaining != null
				? RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE : RiskBasis.PLANNED_SHIPMENT_SCHEDULE;
		PlannedWindow plannedWindow = planned == null ? null : new PlannedWindow(
				switch (planned.status()) {
					case AVAILABLE -> WindowState.AVAILABLE;
					case INVALID -> WindowState.INVALID;
					case INCOMPLETE -> WindowState.INCOMPLETE;
				}, planned.window() == null ? null : planned.window().totalSeconds());
		RemainingWindow remainingWindow = remaining == null ? null : new RemainingWindow(remaining.status().name(),
				remaining.totalSeconds(), remaining.missedBySeconds());
		ArrivalEstimateFact estimateFact;
		if (estimate == null) estimateFact = new ArrivalEstimateFact(EstimateState.UNKNOWN, null, null, null);
		else if (estimate.unavailableReason() != null) estimateFact = new ArrivalEstimateFact(
				EstimateState.UNAVAILABLE, null, null, estimate.unavailableReason().code());
		else estimateFact = new ArrivalEstimateFact(Boolean.TRUE.equals(estimate.possiblyOutdated())
				? EstimateState.STALE : EstimateState.AVAILABLE, estimate.estimatedArrivalAt(),
				estimate.lastReceivedAt(), null);
		String unavailable = assessed ? null : planned == null ? "RISK_NOT_RECORDED" : "PLANNED_WINDOW_" + planned.status();
		return new MaritimeAiConnectionRiskContext(assessed, unavailable,
				shipment.risk() == null ? null : shipment.risk().level(), basis, plannedWindow,
				remainingWindow, estimateFact, List.of());
	}

	private static ArrivalEstimateFact arrivalEstimate(com.drift.backend.shipment.ArrivalEstimate value) {
		if (value == null) return new ArrivalEstimateFact(EstimateState.UNKNOWN, null, null, null);
		if (value.unavailableReason() != null) return new ArrivalEstimateFact(EstimateState.UNAVAILABLE,
				null, null, value.unavailableReason().code());
		return new ArrivalEstimateFact(Boolean.TRUE.equals(value.possiblyOutdated()) ? EstimateState.STALE : EstimateState.AVAILABLE,
				value.estimatedArrivalAt(), value.lastReceivedAt(), null);
	}

	private static List<String> assumptions(AssessmentExplanation explanation) {
		return explanation == null || explanation.assumptions() == null ? List.of() : explanation.assumptions();
	}
}
