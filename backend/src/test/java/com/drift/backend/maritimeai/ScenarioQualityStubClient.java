package com.drift.backend.maritimeai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import tools.jackson.databind.ObjectMapper;

/** Offline-only formatter for the authenticated scenario-endpoint quality matrix. */
public final class ScenarioQualityStubClient extends ConfiguredMaritimeAiClient {
	private final List<MaritimeAiEvidencePackage> scenarioInputs = new ArrayList<>();
	private final SyntheticDisruptionAiClient ordinaryClient;

	public ScenarioQualityStubClient() {
		this(new SyntheticDisruptionAiClient());
	}

	public ScenarioQualityStubClient(SyntheticDisruptionAiClient ordinaryClient) {
		super("offline-scenario-quality-stub", "deterministic-fixture", "", "", false, 20, 800,
				new ObjectMapper(), (request, body) -> {
					throw new AssertionError("The offline scenario stub must never invoke an HTTP transport.");
				});
		this.ordinaryClient = ordinaryClient;
	}

	@Override
	public synchronized MaritimeAiClientResult interpret(MaritimeAiEvidencePackage input) {
		if (input.shipmentContext() == null || input.shipmentContext().scenario() == null)
			return ordinaryClient.interpret(input);
		scenarioInputs.add(input);
		return new MaritimeAiClientResult("offline-scenario-quality-stub", "deterministic-fixture",
				SCENARIO_PROMPT_VERSION, interpretation(input), null);
	}

	public synchronized List<MaritimeAiEvidencePackage> scenarioInputs() {
		return List.copyOf(scenarioInputs);
	}

	public synchronized void clearScenarioInputs() {
		scenarioInputs.clear();
	}

	private static MaritimeAiInterpretation interpretation(MaritimeAiEvidencePackage input) {
		var context = Objects.requireNonNull(input.shipmentContext());
		var risk = Objects.requireNonNull(context.connectionRisk());
		var revision = input.evidence().stream()
				.filter(item -> "MPA_ETA_REVISION".equals(item.sourceType())
						&& "MOTHER".equals(item.vesselRole()) && "EXPECTED_ARRIVAL".equals(item.observationType()))
				.findFirst().orElseThrow(() -> new IllegalArgumentException("Scenario fixture revision is required."));

		String window = risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE
				? "AIS-estimated remaining connection window" : "planned connection window";
		String freshness = switch (risk.arrivalEstimate().state()) {
			case AVAILABLE -> "available";
			case STALE -> "stale";
			case UNAVAILABLE -> "unavailable";
			case UNKNOWN -> "unknown";
		};
		String riskStatement = "The synthetic expected-arrival revision warrants human review alongside the backend "
				+ risk.riskLevel().name() + " " + window + "; the AIS estimate is " + freshness + ".";
		List<String> riskReferences = new ArrayList<>(MaritimeAiCanonicalFactReferences.riskReferences(input));
		riskReferences.add("evidence:" + revision.evidenceId());
		var riskClaim = new MaritimeAiClaim(riskStatement, List.of(revision.evidenceId()), "CONTEXTUAL",
				revision.vesselRole(), revision.observationType(), revision.associationScope(), riskReferences,
				"RISK_BUFFER_REVIEW", riskReview(risk));
		var business = Objects.requireNonNull(context.businessContext());
		var comparison = Objects.requireNonNull(business.comparison());
		MaritimeAiClaim delayClaim = null;
		if (comparison.state() == MaritimeAiBusinessContext.ComparisonState.AVAILABLE) {
			String outcomeStatement = switch (Objects.requireNonNull(comparison.outcome())) {
				case EXCEEDS_DEADLINE -> "The backend comparison shows the predicted arrival exceeds the applicable transshipment-arrival deadline.";
				case NO_PREDICTED_IMPACT -> "The backend comparison shows no predicted impact on the applicable transshipment-arrival deadline.";
			};
			String statement = "The synthetic expected-arrival revision warrants review; " + outcomeStatement;
			List<String> comparisonReferences = new ArrayList<>(MaritimeAiCanonicalFactReferences.businessReferences(input));
			comparisonReferences.add("evidence:" + revision.evidenceId());
			delayClaim = new MaritimeAiClaim(statement, List.of(revision.evidenceId()), "CONTEXTUAL",
					revision.vesselRole(), revision.observationType(), revision.associationScope(), comparisonReferences,
					"DELAY_COMPARISON_REVIEW");
		}

		return new MaritimeAiInterpretation("Review the grounded interpretation and limitations.",
				delayClaim == null ? List.of(riskClaim) : List.of(riskClaim, delayClaim),
				List.of("The synthetic maritime observation does not establish an actual arrival or explain the backend risk calculation."),
				List.of(
						"Ask the carrier operations team to verify whether the latest arrival estimate remains current at the scenario cutoff.",
						"Ask the operations team to confirm whether the transshipment-arrival deadline remains applicable to the current shipment port."),
				true, null);
	}

	private static MaritimeAiClaim.RiskReview riskReview(MaritimeAiConnectionRiskContext risk) {
		MaritimeAiClaim.RiskReview.SelectedWindowState selectedWindowState;
		if (risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE) {
			selectedWindowState = risk.remainingWindow() != null && "AVAILABLE".equals(risk.remainingWindow().status())
					? MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE
					: MaritimeAiClaim.RiskReview.SelectedWindowState.NO_REMAINING_WINDOW;
		} else {
			selectedWindowState = switch (risk.plannedWindow().state()) {
				case AVAILABLE -> MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE;
				case INVALID -> MaritimeAiClaim.RiskReview.SelectedWindowState.INVALID;
				case INCOMPLETE -> MaritimeAiClaim.RiskReview.SelectedWindowState.INCOMPLETE;
			};
		}
		return new MaritimeAiClaim.RiskReview(risk.riskLevel(), risk.riskBasis(), selectedWindowState,
				risk.arrivalEstimate().state());
	}
}
