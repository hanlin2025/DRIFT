package com.drift.backend.maritimeai;

import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import com.drift.backend.maritimecontext.MaritimeEvidence;

/** Offline-only demo client. It is selected only by an explicit local/staging property. */
@Component
@Primary
@ConditionalOnProperty(prefix = "app.maritime-ai", name = "controlled-replay-stub-enabled", havingValue = "true")
class ControlledScenarioReplayAiClient implements MaritimeAiClient {
	private static final String SCENARIO_REVISION = "cdg279-v1-revision-1";
	private static final String SCENARIO_NOTICE = "cdg279-v1-notice-2";
	private static final String SCENARIO_WEATHER = "cdg279-v1-weather-2";
	private static final Set<String> SCENARIO_EVIDENCE = Set.of(SCENARIO_REVISION, SCENARIO_NOTICE, SCENARIO_WEATHER);
	private static final String MPA = "demo-synthetic-mpa-schedule-001";
	private static final String NOTICE = "demo-synthetic-notice-001";
	private static final String NEA = "demo-synthetic-nea-rainfall-001";
	private static final Set<String> REQUIRED = Set.of(MPA, NOTICE, NEA);

	@Override public MaritimeAiClientResult interpret(MaritimeAiEvidencePackage input) {
		if (input.shipmentContext() != null && input.shipmentContext().scenario() != null) {
			return interpretControlledScenario(input);
		}
		Set<String> ids = input.evidence().stream().map(item -> item.evidenceId()).collect(java.util.stream.Collectors.toSet());
		if (!REQUIRED.equals(ids) || input.evidence().stream().anyMatch(item -> !"SYNTHETIC_DEMONSTRATION".equals(item.sourceMode()))) {
			return MaritimeAiClientResult.unavailable("Controlled replay stub accepts only its three synthetic evidence records.");
		}
		return new MaritimeAiClientResult("controlled-replay-stub", "offline", "cdg249.controlled-replay.stub.v1",
				new MaritimeAiInterpretation("Simulated planning interpretation using separately displayed fictional schedule and source evidence.", List.of(
						new MaritimeAiClaim("Review the documented schedule change against the separately displayed planning context.", List.of(MPA), "CONTEXTUAL",
								"MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION", List.of("evidence:" + MPA,
										"schedule:MOTHER:PLANNED_ARRIVAL", "schedule:FEEDER:PLANNED_DEPARTURE"), "SCHEDULE_REVIEW"),
						new MaritimeAiClaim("The synthetic port-area maintenance notice overlaps the revised expected arrival as contextual information only.", List.of(NOTICE), "CONTEXTUAL",
								null, null, "PORT_CONTEXT", List.of("evidence:" + NOTICE,
										MaritimeAiCanonicalFactReferences.PORT_OVERLAP_REFERENCE), "PORT_TIME_OVERLAP_REVIEW"),
						new MaritimeAiClaim("The synthetic rainfall observation is regional context only.", List.of(NEA), "CONTEXTUAL",
								null, null, "REGIONAL_CONTEXT", List.of("evidence:" + NEA), "REGIONAL_WEATHER_CONTEXT")),
						List.of("All records are a controlled fictional scenario and do not establish a real shipment impact.",
								"The notice and regional weather do not establish a cause of the expected-arrival revision."),
						List.of("A human should review the fictional schedule relationship before any operational decision."), true, null), null);
	}

	private MaritimeAiClientResult interpretControlledScenario(MaritimeAiEvidencePackage input) {
		Set<String> ids = input.evidence().stream().map(item -> item.evidenceId()).collect(java.util.stream.Collectors.toSet());
		if (ids.isEmpty() || !SCENARIO_EVIDENCE.containsAll(ids)
				|| input.evidence().stream().anyMatch(item -> !"SYNTHETIC_DEMONSTRATION".equals(item.sourceMode()))) {
			return MaritimeAiClientResult.unavailable("Controlled scenario stub accepts only the known fictional CDG-279 evidence records.");
		}
		List<MaritimeAiClaim> claims = new java.util.ArrayList<>();
		MaritimeEvidence revision = input.evidence().stream()
				.filter(item -> SCENARIO_REVISION.equals(item.evidenceId())).findFirst().orElse(null);
		MaritimeAiBusinessContext business = input.shipmentContext().businessContext();
		if (revision == null && business != null && business.comparison() != null
				&& business.comparison().state() == MaritimeAiBusinessContext.ComparisonState.AVAILABLE) {
			return MaritimeAiClientResult.unavailable(
					"The controlled scenario stub cannot interpret a backend comparison without the eligible schedule revision evidence.");
		}
		if (revision != null && hasPlannedSchedule(input.shipmentContext())) {
			claims.add(new MaritimeAiClaim(
					"The documented mother-vessel expected-arrival revision warrants review against the planned vessel schedule.",
					List.of(revision.evidenceId()), "CONTEXTUAL", "MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION",
					List.of("evidence:" + revision.evidenceId(), "schedule:MOTHER:PLANNED_ARRIVAL",
							"schedule:FEEDER:PLANNED_DEPARTURE"), "SCHEDULE_REVIEW"));
		}
		if (revision != null) {
			if (business != null && business.deadline() != null
					&& business.deadline().state() == MaritimeAiBusinessContext.DeadlineState.RECORDED
					&& Boolean.TRUE.equals(business.deadline().applicableToCurrentPort())
					&& business.comparison() != null
					&& business.comparison().state() == MaritimeAiBusinessContext.ComparisonState.AVAILABLE
					&& business.comparison().outcome() != null) {
				List<String> references = new java.util.ArrayList<>();
				references.add("evidence:" + revision.evidenceId());
				references.addAll(MaritimeAiCanonicalFactReferences.businessReferences(input));
				String outcome = business.comparison().outcome() == com.drift.backend.shipment.DelayComparison.Outcome.EXCEEDS_DEADLINE
						? "the backend comparison shows the predicted arrival exceeds the applicable scenario-defined transshipment-arrival deadline"
						: "the backend comparison shows no predicted impact against the applicable scenario-defined transshipment-arrival deadline";
				claims.add(new MaritimeAiClaim("Review the cited synthetic source observation alongside the scenario facts; " + outcome + ".",
						List.of(revision.evidenceId()), "CONTEXTUAL", revision.vesselRole(),
						revision.observationType(), revision.associationScope(), references,
						"DELAY_COMPARISON_REVIEW"));
			}
		}
		MaritimeEvidence notice = input.evidence().stream()
				.filter(item -> SCENARIO_NOTICE.equals(item.evidenceId())).findFirst().orElse(null);
		if (notice != null) {
			claims.add(new MaritimeAiClaim("The synthetic port-area notice is contextual information only and does not establish a vessel event.",
					List.of(notice.evidenceId()), "CONTEXTUAL", null, null, notice.associationScope(),
					List.of("evidence:" + notice.evidenceId()), "PORT_CONTEXT_REVIEW"));
		}
		MaritimeEvidence weather = input.evidence().stream()
				.filter(item -> SCENARIO_WEATHER.equals(item.evidenceId())).findFirst().orElse(null);
		if (weather != null) {
			claims.add(new MaritimeAiClaim("The synthetic regional weather record remains regional context only.",
					List.of(weather.evidenceId()), "CONTEXTUAL", null, null, weather.associationScope(),
					List.of("evidence:" + weather.evidenceId()), "REGIONAL_WEATHER_CONTEXT"));
		}
		if (claims.isEmpty()) return MaritimeAiClientResult.unavailable("The controlled scenario stub has no eligible scenario facts to interpret.");
		MaritimeAiInterpretation output = new MaritimeAiInterpretation(
				"Review the grounded interpretation and limitations.", claims,
				List.of("Synthetic evidence does not establish a real vessel event or its cause."),
				List.of("Ask the operations team to verify whether the latest arrival estimate remains current at the scenario cutoff.",
						"Ask the operations team to confirm whether the scenario-defined transshipment-arrival deadline remains applicable to the current shipment port."),
				true, null);
		return new MaritimeAiClientResult("controlled-replay-stub", "offline", "cdg290.offline-scenario-stub.v1", output, null);
	}

	private static boolean hasPlannedSchedule(MaritimeAiShipmentContext context) {
		return context != null && context.motherVessel() != null && context.motherVessel().plannedTime() != null
				&& context.feederVessel() != null && context.feederVessel().plannedTime() != null;
	}
}
