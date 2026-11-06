package com.drift.backend.maritimeai;

import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Offline-only demo client. It is selected only by an explicit local/staging property. */
@Component
@Primary
@ConditionalOnProperty(prefix = "app.maritime-ai", name = "controlled-replay-stub-enabled", havingValue = "true")
class ControlledScenarioReplayAiClient implements MaritimeAiClient {
	private static final String MPA = "demo-synthetic-mpa-schedule-001";
	private static final String NOTICE = "demo-synthetic-notice-001";
	private static final String NEA = "demo-synthetic-nea-rainfall-001";
	private static final Set<String> REQUIRED = Set.of(MPA, NOTICE, NEA);

	@Override public MaritimeAiClientResult interpret(MaritimeAiEvidencePackage input) {
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
}
