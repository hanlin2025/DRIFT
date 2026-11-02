package com.drift.backend.maritimeai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.shipment.DelayComparison;

/** Offline test client whose claims are copied only from typed context and eligible fixture rows. */
public final class SyntheticDisruptionAiClient implements MaritimeAiClient {
	private final List<MaritimeAiEvidencePackage> inputs = new ArrayList<>();

	@Override
	public synchronized MaritimeAiClientResult interpret(MaritimeAiEvidencePackage input) {
		inputs.add(input);
		MaritimeEvidence revision = input.evidence().stream()
				.filter(item -> "cdg279-v1-revision-1".equals(item.evidenceId())).findFirst().orElseThrow();
		MaritimeAiConnectionRiskContext risk = Objects.requireNonNull(input.shipmentContext().connectionRisk());
		List<String> revisionRiskReferences = new ArrayList<>(MaritimeAiCanonicalFactReferences.riskReferences(input));
		revisionRiskReferences.add("evidence:" + revision.evidenceId());
		MaritimeAiClaim.RiskReview riskReview = riskReview(risk);
		String freshness = switch (risk.arrivalEstimate().state()) {
			case AVAILABLE -> "fresh";
			case STALE -> "stale";
			case UNAVAILABLE -> "unavailable";
			case UNKNOWN -> "unknown";
		};
		String riskStatement = "The recorded expected-arrival revision warrants review alongside the backend "
				+ risk.riskLevel().name() + " AIS-estimated remaining connection window; the AIS arrival estimate is " + freshness + ".";
		List<MaritimeAiClaim> claims = new ArrayList<>();
		claims.add(new MaritimeAiClaim(riskStatement, List.of(revision.evidenceId()), "CONTEXTUAL", revision.vesselRole(),
				revision.observationType(), revision.associationScope(), revisionRiskReferences,
				"RISK_BUFFER_REVIEW", riskReview));

		input.evidence().stream().filter(item -> "cdg279-v1-notice-2".equals(item.evidenceId())).findFirst().ifPresent(notice ->
				claims.add(new MaritimeAiClaim("The fictional port-area notice is contextual information only.",
						List.of(notice.evidenceId()), "CONTEXTUAL", null, null, notice.associationScope(),
						List.of("evidence:" + notice.evidenceId()), "PORT_CONTEXT_REVIEW")));
		input.evidence().stream().filter(item -> "cdg279-v1-weather-2".equals(item.evidenceId())).findFirst().ifPresent(weather ->
				claims.add(new MaritimeAiClaim("The fictional regional weather record is contextual information only.",
						List.of(weather.evidenceId()), "CONTEXTUAL", null, null, weather.associationScope(),
						List.of("evidence:" + weather.evidenceId()), "REGIONAL_WEATHER_CONTEXT")));

		MaritimeAiBusinessContext business = input.shipmentContext().businessContext();
		if (business != null && business.deadline() != null
				&& business.deadline().state() == MaritimeAiBusinessContext.DeadlineState.RECORDED
				&& business.comparison() != null
				&& business.comparison().state() == MaritimeAiBusinessContext.ComparisonState.AVAILABLE) {
			String outcomeText = business.comparison().outcome() == DelayComparison.Outcome.EXCEEDS_DEADLINE
					? "the predicted arrival exceeds the applicable transshipment-arrival deadline"
					: "no predicted impact is shown against the applicable transshipment-arrival deadline";
			List<String> businessReferences = new ArrayList<>(MaritimeAiCanonicalFactReferences.businessReferences(input));
			businessReferences.add("evidence:" + revision.evidenceId());
			claims.add(new MaritimeAiClaim("The recorded revision warrants review alongside the deterministic comparison: "
					+ outcomeText + ".", List.of(revision.evidenceId()), "CONTEXTUAL", revision.vesselRole(),
					revision.observationType(), revision.associationScope(), businessReferences, "DELAY_COMPARISON_REVIEW"));
		}

		MaritimeAiInterpretation interpretation = new MaritimeAiInterpretation(
				"This interpretation reviews the supplied synthetic maritime observations alongside the backend connection-risk calculation.",
				List.copyOf(claims), List.of("The synthetic records do not verify an actual vessel arrival."),
				List.of("Ask the operations team to verify the synthetic revision against its fictional source citation."),
				true, null);
		return new MaritimeAiClientResult("offline-synthetic-context-test", "typed-context-formatter",
				"cdg279.synthetic-offline.v1", interpretation, null);
	}

	public synchronized List<MaritimeAiEvidencePackage> inputs() { return List.copyOf(inputs); }
	public synchronized void clear() { inputs.clear(); }

	private static MaritimeAiClaim.RiskReview riskReview(MaritimeAiConnectionRiskContext risk) {
		MaritimeAiClaim.RiskReview.SelectedWindowState windowState;
		if (risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE) {
			windowState = risk.remainingWindow() != null && "AVAILABLE".equals(risk.remainingWindow().status())
					? MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE
					: MaritimeAiClaim.RiskReview.SelectedWindowState.NO_REMAINING_WINDOW;
		} else {
			windowState = switch (risk.plannedWindow().state()) {
				case AVAILABLE -> MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE;
				case INVALID -> MaritimeAiClaim.RiskReview.SelectedWindowState.INVALID;
				case INCOMPLETE -> MaritimeAiClaim.RiskReview.SelectedWindowState.INCOMPLETE;
			};
		}
		return new MaritimeAiClaim.RiskReview(risk.riskLevel(), risk.riskBasis(), windowState, risk.arrivalEstimate().state());
	}
}
