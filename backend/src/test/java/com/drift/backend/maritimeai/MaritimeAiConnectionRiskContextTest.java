package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import com.drift.backend.shipment.ArrivalEstimate;
import com.drift.backend.shipment.AssessmentExplanation;
import com.drift.backend.shipment.ConnectionWindow;
import com.drift.backend.shipment.RemainingConnectionWindow;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentRisk;
import com.drift.backend.shipment.ShipmentRiskContribution;
import com.drift.backend.shipment.ShipmentRiskContributions;

class MaritimeAiConnectionRiskContextTest {
	private static final Instant RECEIVED = Instant.parse("2026-10-26T04:00:00Z");

	@Test void anAvailableFreshAisEstimateIsNamedAsTheSourceOfRemainingBufferAndSeverity() {
		var detail = detail(new ConnectionWindow("2 days", 172800),
				new RemainingConnectionWindow(RemainingConnectionWindow.Status.AVAILABLE, "2 hours", 7200L, null, null),
				new ShipmentRiskContributions(true, null, List.of(
						contribution(ShipmentRiskContribution.Code.WINDOW_IN_USE, "Remaining connection window"),
						contribution(ShipmentRiskContribution.Code.REMAINING_DURATION, "2 hours"))),
				freshEstimate(), new ShipmentRisk(ShipmentRisk.Level.HIGH, "backend output"),
				new AssessmentExplanation(List.of("Remaining connection window"), List.of("The estimated arrival was used."),
						"The estimated arrival was used.", null));

		var context = MaritimeAiConnectionRiskContext.from(detail);
		assertThat(context.assessed()).isTrue();
		assertThat(context.riskLevel()).isEqualTo(ShipmentRisk.Level.HIGH);
		assertThat(context.riskBasis()).isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE);
		assertThat(context.plannedWindow()).isEqualTo(new MaritimeAiConnectionRiskContext.PlannedWindow(
				MaritimeAiConnectionRiskContext.WindowState.AVAILABLE, 172800L));
		assertThat(context.remainingWindow()).isEqualTo(new MaritimeAiConnectionRiskContext.RemainingWindow("AVAILABLE", 7200L, null));
		assertThat(context.arrivalEstimate()).isEqualTo(new MaritimeAiConnectionRiskContext.ArrivalEstimateFact(
				MaritimeAiConnectionRiskContext.EstimateState.AVAILABLE,
				OffsetDateTime.parse("2026-10-26T06:00:00Z"), RECEIVED, null));
		assertThat(MaritimeAiCanonicalFactReferences.allowed(input(context))).contains(
				"risk:CONNECTION_SEVERITY", "risk:PLANNED_CONNECTION_WINDOW",
				"risk:REMAINING_CONNECTION_WINDOW", "risk:AIS_ESTIMATED_ARRIVAL");
		var snapshot = snapshot(context);
		assertThat(snapshot.severity()).isEqualTo(ShipmentRisk.Level.HIGH);
		assertThat(snapshot.selectedWindowBasis()).isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE);
		assertThat(snapshot.selectedWindowState()).isEqualTo(MaritimeAiDeterministicSummary.RiskSnapshot.SelectedWindowState.AVAILABLE);
		assertThat(snapshot.windowSeconds()).isEqualTo(7200L);
		assertThat(snapshot.estimateFreshness()).isEqualTo(MaritimeAiConnectionRiskContext.EstimateState.AVAILABLE);
		assertThat(snapshot.calculationLimitations()).contains("The estimated arrival was used.");
		assertThat(snapshot.canonicalFactReferences()).contains("risk:CONNECTION_SEVERITY", "risk:REMAINING_CONNECTION_WINDOW", "risk:AIS_ESTIMATED_ARRIVAL");
	}

	@Test void anUnavailableEstimateFallsBackToThePlannedWindowWithoutInventingRemainingBuffer() {
		var detail = detail(new ConnectionWindow("18 hours", 64800), null,
				new ShipmentRiskContributions(true, null, List.of(
						contribution(ShipmentRiskContribution.Code.WINDOW_IN_USE, "Planned connection window"),
						contribution(ShipmentRiskContribution.Code.PLANNED_DURATION, "18 hours"),
						contribution(ShipmentRiskContribution.Code.ESTIMATE_NOT_USED, "No AIS position"))),
				new ArrivalEstimate(null, null, null, null, null, null, null, null, null,
						new ArrivalEstimate.UnavailableReason("NO_POSITION", "No AIS position has been received for the mother vessel.")),
				new ShipmentRisk(ShipmentRisk.Level.MEDIUM, "backend output"),
				new AssessmentExplanation(List.of("Planned connection window"), List.of("Planned schedule used."),
						"Confidence is limited.", null));

		var context = MaritimeAiConnectionRiskContext.from(detail);
		assertThat(context.riskBasis()).isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.PLANNED_SHIPMENT_SCHEDULE);
		assertThat(context.remainingWindow()).isNull();
		assertThat(context.arrivalEstimate().state()).isEqualTo(MaritimeAiConnectionRiskContext.EstimateState.UNAVAILABLE);
		assertThat(context.arrivalEstimate().unavailableReason()).isEqualTo("NO_POSITION");
		assertThat(MaritimeAiCanonicalFactReferences.allowed(input(context))).contains("risk:ARRIVAL_ESTIMATE_NOT_USED")
				.doesNotContain("risk:AIS_ESTIMATED_ARRIVAL", "risk:REMAINING_CONNECTION_WINDOW");
		assertThat(snapshot(context).estimateFreshness()).isEqualTo(MaritimeAiConnectionRiskContext.EstimateState.UNAVAILABLE);
		assertThat(snapshot(context).estimateUnavailableCode()).isEqualTo("NO_POSITION");
	}

	@Test void aStaleEstimateIsPreservedButCannotBecomeTheRiskBasisOrRemainingBuffer() {
		var detail = detail(new ConnectionWindow("1 day", 86400), null,
				new ShipmentRiskContributions(true, null, List.of(
						contribution(ShipmentRiskContribution.Code.WINDOW_IN_USE, "Planned connection window"),
						contribution(ShipmentRiskContribution.Code.PLANNED_DURATION, "1 day"),
						contribution(ShipmentRiskContribution.Code.ESTIMATE_NOT_USED, "Position is more than six hours old"))),
				new ArrivalEstimate(OffsetDateTime.parse("2026-10-26T06:00:00Z"), ArrivalEstimate.LIMITATION,
						new BigDecimal("12.0"), ArrivalEstimate.Classification.ON_SCHEDULE, "0", 0L, 0,
						RECEIVED, true, null), new ShipmentRisk(ShipmentRisk.Level.LOW, "backend output"),
				new AssessmentExplanation(List.of("Planned connection window"), List.of("Stale estimate not used."),
						"Confidence is limited.", null));

		var context = MaritimeAiConnectionRiskContext.from(detail);
		assertThat(context.riskBasis()).isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.PLANNED_SHIPMENT_SCHEDULE);
		assertThat(context.arrivalEstimate().state()).isEqualTo(MaritimeAiConnectionRiskContext.EstimateState.STALE);
		assertThat(context.arrivalEstimate().positionReceivedAt()).isEqualTo(RECEIVED);
		assertThat(context.remainingWindow()).isNull();
		assertThat(MaritimeAiCanonicalFactReferences.allowed(input(context))).doesNotContain("risk:AIS_ESTIMATED_ARRIVAL");
		assertThat(snapshot(context).estimateFreshness()).isEqualTo(MaritimeAiConnectionRiskContext.EstimateState.STALE);
	}

	@Test void anInvalidPlannedWindowHasNoDurationAndAnAisBasedMissRetainsOnlyMissedSeconds() {
		var invalid = detail(null, null,
				new ShipmentRiskContributions(true, null, List.of(
						contribution(ShipmentRiskContribution.Code.WINDOW_IN_USE, "Planned connection window"),
						contribution(ShipmentRiskContribution.Code.PLANNED_WINDOW, "The planned connection window is not positive."),
						contribution(ShipmentRiskContribution.Code.ESTIMATE_NOT_USED, "No AIS position"))),
				new ArrivalEstimate(null, null, null, null, null, null, null, null, null,
						new ArrivalEstimate.UnavailableReason("NO_POSITION", "No position.")),
				new ShipmentRisk(ShipmentRisk.Level.CRITICAL, "backend output"),
				new AssessmentExplanation(List.of("Planned connection window"), List.of("The planned window is not positive."),
						"Confidence is limited.", null));
		var invalidContext = MaritimeAiConnectionRiskContext.from(invalid);
		assertThat(invalidContext.plannedWindow()).isEqualTo(new MaritimeAiConnectionRiskContext.PlannedWindow(
				MaritimeAiConnectionRiskContext.WindowState.INVALID, null));
		assertThat(MaritimeAiCanonicalFactReferences.allowed(input(invalidContext))).contains("risk:PLANNED_CONNECTION_WINDOW");

		var missed = detail(new ConnectionWindow("2 days", 172800),
				new RemainingConnectionWindow(RemainingConnectionWindow.Status.MISSED, null, null, "less than 1 second", 0L),
				new ShipmentRiskContributions(true, null, List.of(
						contribution(ShipmentRiskContribution.Code.WINDOW_IN_USE, "Remaining connection window"),
						contribution(ShipmentRiskContribution.Code.MISSED_BY, "less than 1 second"))),
				freshEstimate(), new ShipmentRisk(ShipmentRisk.Level.CRITICAL, "backend output"),
				new AssessmentExplanation(List.of("Remaining connection window"), List.of("The estimated arrival was used."),
						"The estimated arrival was used.", null));

		var context = MaritimeAiConnectionRiskContext.from(missed);
		assertThat(context.plannedWindow().state()).isEqualTo(MaritimeAiConnectionRiskContext.WindowState.AVAILABLE);
		assertThat(context.remainingWindow()).isEqualTo(new MaritimeAiConnectionRiskContext.RemainingWindow("MISSED", null, 0L));
		assertThat(context.riskBasis()).isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE);
		assertThat(MaritimeAiCanonicalFactReferences.allowed(input(context))).contains("risk:REMAINING_CONNECTION_WINDOW");
		assertThat(snapshot(context).selectedWindowState()).isEqualTo(MaritimeAiDeterministicSummary.RiskSnapshot.SelectedWindowState.MISSED);
		assertThat(snapshot(context).missedBySeconds()).isEqualTo(0L);
	}

	@Test void missingScheduleIsUnassessedAndHasNoSeverityOrWindowReference() {
		var detail = detail(null, null, new ShipmentRiskContributions(false,
				"Connection risk is not assessed because the planned feeder-vessel departure is missing.", List.of()),
				null, null, null);
		var context = MaritimeAiConnectionRiskContext.from(detail);
		assertThat(context.assessed()).isFalse();
		assertThat(context.riskLevel()).isNull();
		assertThat(context.riskBasis()).isNull();
		assertThat(context.plannedWindow().state()).isEqualTo(MaritimeAiConnectionRiskContext.WindowState.INCOMPLETE);
		assertThat(MaritimeAiCanonicalFactReferences.allowed(input(context))).isEmpty();
		assertThat(snapshot(context).selectedWindowState()).isEqualTo(MaritimeAiDeterministicSummary.RiskSnapshot.SelectedWindowState.INCOMPLETE);
		assertThat(snapshot(context).unavailableReason()).contains("planned feeder-vessel departure is missing");
	}

	@Test void historicalMpaEvidenceKeepsItsEvidenceReferenceAndCannotChangeRiskBasis() {
		var context = MaritimeAiConnectionRiskContext.from(detail(new ConnectionWindow("2 days", 172800),
				new RemainingConnectionWindow(RemainingConnectionWindow.Status.AVAILABLE, "2 hours", 7200L, null, null),
				new ShipmentRiskContributions(true, null, List.of(
						contribution(ShipmentRiskContribution.Code.WINDOW_IN_USE, "Remaining connection window"),
						contribution(ShipmentRiskContribution.Code.REMAINING_DURATION, "2 hours"))),
				freshEstimate(), new ShipmentRisk(ShipmentRisk.Level.HIGH, "backend output"),
				new AssessmentExplanation(List.of("Remaining connection window"), List.of("The estimated arrival was used."),
						"The estimated arrival was used.", null)));
		var mpa = new com.drift.backend.maritimecontext.MaritimeEvidence("MPA_ETA_REVISION", "RETROSPECTIVE_ANALYSIS",
				"mpa-historical", "Historical revision", "source", "", "manifest", "2026-10-26T01:00:00Z",
				"2026-10-26T01:00:00Z", "2026-10-26T02:00:00Z", "FRESH", "old", "new", "+1 hour",
				"VESSEL_CONTEXT", "VESSEL_OBSERVATION", "COMPATIBLE", "COMPATIBLE", true, true,
				"UNVERIFIED_IMO_ONLY", "Historical source does not enter the risk calculation.");
		var input = new MaritimeAiEvidencePackage("v1", "2026-10-26T08:00:00Z", "UNVERIFIED_IMO_ONLY",
				new MaritimeAiShipmentContext("Singapore", null, null, context), List.of(mpa));
		assertThat(context.riskBasis()).isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE);
		assertThat(MaritimeAiCanonicalFactReferences.allowed(input)).contains("risk:AIS_ESTIMATED_ARRIVAL", "evidence:mpa-historical");
		assertThat(input.shipmentContext().connectionRisk().arrivalEstimate().estimatedArrivalAt())
				.isEqualTo(OffsetDateTime.parse("2026-10-26T06:00:00Z"));
	}

	@Test void preCdg259ContextJsonRemainsReadable() throws Exception {
		var context = new ObjectMapper().readValue("{\"transshipmentPort\":\"Singapore\",\"motherVessel\":null,\"feederVessel\":null}",
				MaritimeAiShipmentContext.class);
		assertThat(context.connectionRisk()).isNull();
		assertThat(new MaritimeAiShipmentContext("Singapore", null, null).connectionRisk()).isNull();
		var oldSummary = new ObjectMapper().readValue("{\"summaryVersion\":\"cdg247.deterministic-facts.v2.canonical-references\",\"label\":\"old\",\"shipmentScheduleFacts\":[],\"evidenceFacts\":[],\"limitations\":[]}",
				MaritimeAiDeterministicSummary.class);
		assertThat(oldSummary.connectionRisk()).isNull();
	}

	@Test void riskSnapshotOmitsRawAisTimestampsAndMakesMissingContextExplicit() throws Exception {
		var context = MaritimeAiConnectionRiskContext.from(detail(new ConnectionWindow("2 days", 172800),
				new RemainingConnectionWindow(RemainingConnectionWindow.Status.AVAILABLE, "2 hours", 7200L, null, null),
				new ShipmentRiskContributions(true, null, List.of(
						contribution(ShipmentRiskContribution.Code.WINDOW_IN_USE, "Remaining connection window"),
						contribution(ShipmentRiskContribution.Code.REMAINING_DURATION, "2 hours"))), freshEstimate(),
				new ShipmentRisk(ShipmentRisk.Level.HIGH, "backend output"),
				new AssessmentExplanation(List.of("Remaining connection window"), List.of("The estimate was used."), "Limited confidence.", null)));
		String snapshotJson = new ObjectMapper().writeValueAsString(snapshot(context));
		assertThat(snapshotJson).doesNotContain("estimatedArrivalAt", "positionReceivedAt", "2026-10-26T06:00:00Z", RECEIVED.toString());

		var missing = MaritimeAiDeterministicSummary.RiskSnapshot.from(null, List.of());
		assertThat(missing.assessed()).isFalse();
		assertThat(missing.severity()).isNull();
		assertThat(missing.selectedWindowBasis()).isNull();
		assertThat(missing.selectedWindowState()).isEqualTo(MaritimeAiDeterministicSummary.RiskSnapshot.SelectedWindowState.INCOMPLETE);
		assertThat(missing.estimateFreshness()).isEqualTo(MaritimeAiConnectionRiskContext.EstimateState.UNKNOWN);
		assertThat(missing.unavailableReason()).isEqualTo("RISK_CONTEXT_UNAVAILABLE");
	}

	private static MaritimeAiDeterministicSummary.RiskSnapshot snapshot(MaritimeAiConnectionRiskContext context) {
		return MaritimeAiDeterministicSummary.RiskSnapshot.from(context,
				MaritimeAiCanonicalFactReferences.riskReferences(input(context)));
	}

	private static ShipmentRiskContribution contribution(ShipmentRiskContribution.Code code, String value) {
		return new ShipmentRiskContribution(code, code.label(), value);
	}
	private static ArrivalEstimate freshEstimate() {
		return new ArrivalEstimate(OffsetDateTime.parse("2026-10-26T06:00:00Z"), ArrivalEstimate.LIMITATION,
				new BigDecimal("12.0"), ArrivalEstimate.Classification.ON_SCHEDULE, "0", 0L, 0,
				RECEIVED, false, null);
	}
	private static ShipmentDetailResponse detail(ConnectionWindow planned, RemainingConnectionWindow remaining,
			ShipmentRiskContributions contributions, ArrivalEstimate estimate, ShipmentRisk risk,
			AssessmentExplanation explanation) {
		ShipmentDetailResponse detail = mock(ShipmentDetailResponse.class);
		when(detail.connectionWindow()).thenReturn(planned);
		when(detail.remainingConnectionWindow()).thenReturn(remaining);
		when(detail.riskContributions()).thenReturn(contributions);
		when(detail.arrivalComparison()).thenReturn(estimate);
		when(detail.risk()).thenReturn(risk);
		when(detail.assessmentExplanation()).thenReturn(explanation);
		return detail;
	}
	private static MaritimeAiEvidencePackage input(MaritimeAiConnectionRiskContext context) {
		return new MaritimeAiEvidencePackage("v1", "2026-10-26T08:00:00Z", "UNVERIFIED_IMO_ONLY",
				new MaritimeAiShipmentContext("Singapore", null, null, context), List.of());
	}
}
