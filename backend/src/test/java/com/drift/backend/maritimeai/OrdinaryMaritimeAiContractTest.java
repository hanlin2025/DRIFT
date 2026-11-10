package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.shipment.DelayComparison;

import tools.jackson.databind.ObjectMapper;

/**
 * Stubbed contract checks for the ordinary maritime assessment. They do not call a provider and do
 * not establish that a live model produces useful text.
 */
class OrdinaryMaritimeAiContractTest {
	private static final String LIMITATION = "Matching IMO verifies vessel identity only; it does not verify a shipment booking.";
	private static final OffsetDateTime DEADLINE = OffsetDateTime.parse("2026-10-02T10:00:00Z");
	private static final OffsetDateTime LATER_ESTIMATE = OffsetDateTime.parse("2026-10-02T11:00:00Z");
	private static final OffsetDateTime EARLIER_ESTIMATE = OffsetDateTime.parse("2026-10-02T09:00:00Z");

	@Test void ordinaryPromptAsksForGroundedApplicabilityInsteadOfAFixedSentence() {
		AtomicReference<String> requestBody = new AtomicReference<>();
		var client = new ConfiguredMaritimeAiClient("openai-responses", "stub-model", "stub-key",
				"https://example.invalid/v1/responses", true, 20, 800, new ObjectMapper(), (request, body) -> {
					requestBody.set(body);
					return new ConfiguredMaritimeAiClient.TransportResponse(500, "");
				});

		var result = client.interpret(shipment(DelayComparison.Outcome.EXCEEDS_DEADLINE, null,
				MaritimeAiBusinessContext.ComparisonState.AVAILABLE));

		assertThat(result.interpretation()).isNull();
		assertThat(result.promptVersion()).isEqualTo("cdg247.evidence.v21.cdg251-grounded-applicability.1");
		assertThat(requestBody.get())
				.contains("Each claim must explain the cited evidence")
				.contains("Say why that record applies or does not apply")
				.contains("what conflicts or is missing and what a reviewer should verify")
				.contains("not the operational arrival estimate used for delay comparison")
				.contains("exact duration between the supplied estimated arrival and recorded deadline")
				.contains("Shared evidence can have different consequences")
				.contains("maritime_ai_interpretation_v21_cdg251_grounded_applicability")
				.doesNotContain("say only that the documented revision warrants schedule review")
				.doesNotContain("Use only these complete patterns");
	}

	@Test void acceptsACitedMultiSourceExplanationAndKeepsAnIrrelevantNoticeOut() {
		MaritimeAiEvidencePackage input = shipment(DelayComparison.Outcome.EXCEEDS_DEADLINE, null,
				MaritimeAiBusinessContext.ComparisonState.AVAILABLE);
		MaritimeAiInterpretation useful = interpretation(List.of(
				scheduleClaim("This retrospective mother-vessel expected-arrival revision applies to schedule review of the planned feeder departure. "
						+ LIMITATION + " The expected arrival was revised by 60 minutes."),
				weatherClaim("The retrospective regional rainfall observation does not apply to the vessel. It is regional context only and does not establish vessel impact or a cause.")),
				List.of("The retrospective revision and the regional observation do not establish the same condition."),
				List.of("Ask the operations team to verify the latest arrival estimate."), true);

		assertThat(MaritimeAiResponseValidator.validate(useful, input)).isNull();
		assertThat(useful.claims()).extracting(MaritimeAiClaim::evidenceIds)
				.noneMatch(ids -> ids.contains("incompatible-notice"));
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(
				new MaritimeAiClaim("The incompatible notice applies to this vessel.", List.of("incompatible-notice"), "CONTEXTUAL",
						null, null, "PORT_CONTEXT", List.of("evidence:incompatible-notice"), "PORT_CONTEXT_REVIEW")),
				List.of(), List.of(), true), input)).isEqualTo("INELIGIBLE_CITED_EVIDENCE");
	}

	@Test void acceptsAnExactQuotedLimitationWhenShipmentAssociationIsUnverified() {
		MaritimeAiEvidencePackage input = new MaritimeAiEvidencePackage("ordinary-v21", "2026-10-02T08:00:00Z",
				"UNVERIFIED_IMO_ONLY", null, List.of(revision()));
		String explained = "This retrospective mother-vessel expected-arrival revision applies to schedule review. " + LIMITATION;
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(sourceClaim(explained)), List.of(), List.of(), true), input)).isNull();

		String inventedShipment = "This retrospective revision applies to schedule review. It does not verify that this shipment will be delayed.";
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(sourceClaim(inventedShipment)), List.of(), List.of(), true), input))
				.isEqualTo("UNVERIFIED_SHIPMENT_ASSOCIATION");
	}

	@Test void statesDifferentDeadlineConsequencesForTheSameEvidenceAndRejectsASwappedMargin() {
		MaritimeAiEvidencePackage laterDeadlineMiss = shipment(DelayComparison.Outcome.EXCEEDS_DEADLINE, null,
				MaritimeAiBusinessContext.ComparisonState.AVAILABLE);
		MaritimeAiEvidencePackage earlierDeadlineClear = shipment(DelayComparison.Outcome.NO_PREDICTED_IMPACT, null,
				MaritimeAiBusinessContext.ComparisonState.AVAILABLE);
		String exceeds = "The retrospective expected-arrival revision applies as a mother-vessel observation and is not the operational arrival estimate. "
				+ "Review the cited revision alongside the applicable transshipment-arrival deadline: the backend comparison shows the predicted arrival exceeds that deadline by 1 hour.";
		String before = "The retrospective expected-arrival revision applies as a mother-vessel observation and is not the operational arrival estimate. "
				+ "Review the cited revision alongside the applicable transshipment-arrival deadline: the backend comparison shows no predicted impact because the predicted arrival is 1 hour before the applicable transshipment-arrival deadline.";

		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(delayClaim(exceeds, laterDeadlineMiss)),
				List.of(), List.of(), true), laterDeadlineMiss)).isNull();
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(delayClaim(before, earlierDeadlineClear)),
				List.of(), List.of(), true), earlierDeadlineClear)).isNull();
		String swappedOutcome = "The retrospective expected-arrival revision applies as a mother-vessel observation and is not the operational arrival estimate. "
				+ "Review the cited revision alongside the applicable transshipment-arrival deadline: the backend comparison shows the predicted arrival exceeds that deadline.";
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(delayClaim(swappedOutcome, earlierDeadlineClear)),
				List.of(), List.of(), true), earlierDeadlineClear)).isEqualTo("UNSUPPORTED_DELAY_COMPARISON");
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(delayClaim(exceeds.replace("1 hour", "3 hours"), laterDeadlineMiss)),
				List.of(), List.of(), true), laterDeadlineMiss)).isEqualTo("UNSTRUCTURED_FACTUAL_DETAIL");
	}

	@Test void keepsAMissingOrStaleArrivalEstimateUnavailable() {
		MaritimeAiEvidencePackage missing = shipment(null, DelayComparison.Reason.MISSING_PREDICTION,
				MaritimeAiBusinessContext.ComparisonState.UNAVAILABLE);
		String missingText = "The retrospective expected-arrival revision warrants review, but it is not the operational arrival estimate. "
				+ "The applicable transshipment-arrival deadline is recorded for the current transshipment port, and the comparison is unavailable because there is no estimate.";
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(delayClaim(missingText, missing)),
				List.of(), List.of(), true), missing)).isNull();
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(delayClaim(
				"The predicted arrival is 2026-10-02T11:00:00Z.", missing)), List.of(), List.of(), true), missing))
				.isEqualTo("UNSTRUCTURED_FACTUAL_DETAIL");

		MaritimeAiEvidencePackage stale = shipment(null, DelayComparison.Reason.STALE_PREDICTION,
				MaritimeAiBusinessContext.ComparisonState.UNAVAILABLE);
		String staleText = "The retrospective expected-arrival revision warrants review and is not the operational arrival estimate. "
				+ "The applicable transshipment-arrival deadline is recorded for the current transshipment port, and the comparison is unavailable because the arrival estimate is stale.";
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(delayClaim(staleText, stale)),
				List.of(), List.of(), true), stale)).isNull();
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(delayClaim(staleText + " The margin is 3 hours.", stale)),
				List.of(), List.of(), true), stale)).isEqualTo("UNSTRUCTURED_FACTUAL_DETAIL");
	}

	@Test void rejectsFabricatedFactsInvalidCitationsAndMalformedOutput() {
		MaritimeAiEvidencePackage input = shipment(DelayComparison.Outcome.EXCEEDS_DEADLINE, null,
				MaritimeAiBusinessContext.ComparisonState.AVAILABLE);
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(scheduleClaim(
				"The expected arrival was revised by 90 minutes.")), List.of(), List.of(), true), input))
				.isEqualTo("UNSTRUCTURED_FACTUAL_DETAIL");
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(scheduleClaim(
				"The chance of delay is high.")), List.of(), List.of(), true), input))
				.isEqualTo("UNSUPPORTED_OPERATIONAL_IMPACT_CLAIM");
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(scheduleClaim(
				"Reroute the vessel.")), List.of(), List.of(), true), input))
				.isEqualTo("UNSUPPORTED_OPERATIONAL_DECISION");
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(new MaritimeAiClaim(
				"The cited revision applies to schedule review.", List.of("missing"), "CONTEXTUAL")),
				List.of(), List.of(), true), input)).isEqualTo("UNKNOWN_EVIDENCE_ID");
		assertThat(MaritimeAiResponseValidator.validate(new MaritimeAiInterpretation(" ", List.of(), List.of(), List.of(), true, null), input))
				.isEqualTo("MALFORMED_OUTPUT");
	}

	@Test void stillAcceptsAPersistedAbstractScheduleSentence() {
		MaritimeAiEvidencePackage input = shipment(DelayComparison.Outcome.EXCEEDS_DEADLINE, null,
				MaritimeAiBusinessContext.ComparisonState.AVAILABLE);
		assertThat(MaritimeAiResponseValidator.validate(interpretation(List.of(scheduleClaim(
				"The documented revision warrants schedule review.")), List.of(), List.of(), true), input)).isNull();
	}

	private static MaritimeAiClaim scheduleClaim(String statement) {
		return new MaritimeAiClaim(statement, List.of("mpa-revision"), "CONTEXTUAL", "MOTHER", "EXPECTED_ARRIVAL",
				"VESSEL_OBSERVATION", List.of("evidence:mpa-revision", "schedule:MOTHER:PLANNED_ARRIVAL",
						"schedule:FEEDER:PLANNED_DEPARTURE"), "SCHEDULE_REVIEW");
	}

	private static MaritimeAiClaim weatherClaim(String statement) {
		return new MaritimeAiClaim(statement, List.of("regional-weather"), "CONTEXTUAL", null, null, "REGIONAL_CONTEXT",
				List.of("evidence:regional-weather"), "REGIONAL_WEATHER_CONTEXT");
	}

	private static MaritimeAiClaim sourceClaim(String statement) {
		return new MaritimeAiClaim(statement, List.of("mpa-revision"), "UNCERTAIN", "MOTHER", "EXPECTED_ARRIVAL",
				"VESSEL_OBSERVATION", List.of("evidence:mpa-revision"));
	}

	private static MaritimeAiClaim delayClaim(String statement, MaritimeAiEvidencePackage input) {
		List<String> refs = new java.util.ArrayList<>();
		refs.add("evidence:mpa-revision");
		refs.addAll(MaritimeAiCanonicalFactReferences.businessReferences(input));
		return new MaritimeAiClaim(statement, List.of("mpa-revision"), "CONTEXTUAL", "MOTHER", "EXPECTED_ARRIVAL",
				"VESSEL_OBSERVATION", List.copyOf(refs), "DELAY_COMPARISON_REVIEW");
	}

	private static MaritimeAiInterpretation interpretation(List<MaritimeAiClaim> claims, List<String> uncertainties,
			List<String> followUps, boolean sufficient) {
		return new MaritimeAiInterpretation("Review the grounded interpretation and its limitations.", claims,
				uncertainties, followUps, sufficient, null);
	}

	private static MaritimeAiEvidencePackage shipment(DelayComparison.Outcome outcome, DelayComparison.Reason reason,
			MaritimeAiBusinessContext.ComparisonState comparisonState) {
		OffsetDateTime estimate = comparisonState == MaritimeAiBusinessContext.ComparisonState.UNAVAILABLE
				&& reason == DelayComparison.Reason.MISSING_PREDICTION ? null
				: outcome == DelayComparison.Outcome.NO_PREDICTED_IMPACT ? EARLIER_ESTIMATE : LATER_ESTIMATE;
		var deadline = new MaritimeAiBusinessContext.Deadline(MaritimeAiBusinessContext.DeadlineState.RECORDED,
				DEADLINE, "Singapore", true);
		var comparison = new MaritimeAiBusinessContext.Comparison(comparisonState, outcome, reason, estimate,
				DEADLINE, "Singapore");
		var context = new MaritimeAiShipmentContext("Singapore",
				new MaritimeAiShipmentContext.Vessel("MOTHER", "Hidden Mother", "9351048", "PLANNED_ARRIVAL",
						OffsetDateTime.parse("2026-10-02T08:00:00Z")),
				new MaritimeAiShipmentContext.Vessel("FEEDER", "Hidden Feeder", "9351049", "PLANNED_DEPARTURE",
						OffsetDateTime.parse("2026-10-02T18:00:00Z")),
				null, new MaritimeAiBusinessContext(deadline, comparison));
		return new MaritimeAiEvidencePackage("ordinary-v21", "2026-10-02T08:00:00Z", "VERIFIED", context,
				List.of(revision(), weather(), incompatibleNotice()));
	}

	private static MaritimeEvidence revision() {
		return new MaritimeEvidence("MPA_ETA_REVISION", "RETROSPECTIVE_ANALYSIS", "mpa-revision", "Recorded revision",
				"MPA", "", "expected_revisions.csv", "2026-10-02T10:00:00Z", "2026-10-02T09:00:00Z", "2026-10-02T09:00:00Z",
				"HISTORICAL_REPLAY", "2026-10-02T09:00:00Z", "2026-10-02T10:00:00Z", "+60 minutes", "COMPATIBLE",
				"UNKNOWN", "COMPATIBLE", "COMPATIBLE", true, true, "UNVERIFIED_IMO_ONLY", LIMITATION, null, "MOTHER",
				"EXPECTED_ARRIVAL", "VESSEL_OBSERVATION");
	}

	private static MaritimeEvidence weather() {
		return new MaritimeEvidence("NEA_WEATHER", "RETROSPECTIVE_ANALYSIS", "regional-weather", "Recorded rainfall",
				"NEA", "", "rainfall.csv", "2026-10-02T09:00:00Z", "2026-10-02T09:00:00Z", "2026-10-02T09:00:00Z",
				"HISTORICAL_REPLAY", "", "station readings", "", "UNKNOWN", "SINGAPORE_PORT_CONTEXT_ONLY", "COMPATIBLE",
				"COMPATIBLE", true, true, "UNAVAILABLE", "Regional context only; it does not establish weather at a vessel.",
				null, null, null, "REGIONAL_CONTEXT");
	}

	private static MaritimeEvidence incompatibleNotice() {
		return new MaritimeEvidence("MPA_OFFICIAL_NOTICE", "RETROSPECTIVE_ANALYSIS", "incompatible-notice", "Other notice",
				"MPA", "", "notice.csv", "2026-10-02T09:00:00Z", "2026-10-02T09:00:00Z", "2026-10-02T09:00:00Z",
				"HISTORICAL_REPLAY", "", "unrelated", "", "INCOMPATIBLE", "UNKNOWN", "UNKNOWN", "INCOMPATIBLE", false, true,
				"UNAVAILABLE", "This notice is not compatible with the shipment.", null, null, null, "PORT_CONTEXT");
	}
}
