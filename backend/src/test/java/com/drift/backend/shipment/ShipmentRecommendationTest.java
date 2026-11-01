package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ShipmentRecommendationImpact.Outcome;
import com.drift.backend.shipment.ShipmentRecommendedResponse.Code;
import com.drift.backend.shipment.ShipmentRisk.Level;

class ShipmentRecommendationTest {

	private static final OffsetDateTime PLANNED = OffsetDateTime.parse("2026-10-26T12:00:00Z");
	private static final String OTHER_IMPACT = "The predicted arrival and the recorded deadline are for different milestones.";

	private final ConnectionWindowService windows = new ConnectionWindowService();

	@Test
	void lowRiskWithNoPredictedImpactIsTheOnlyNoAction() {
		ShipmentRecommendation recommendation = recommend(low(), ShipmentRecommendationImpact.noPredictedImpact());

		assertThat(recommendation.available()).isTrue();
		assertThat(recommendation.unavailable()).isNull();
		assertThat(recommendation.response().code()).isEqualTo(Code.NO_ACTION);
		assertThat(recommendation.response().label()).isEqualTo("No action");
		assertThat(recommendation.response().wording()).isEqualTo(
				"No action is needed. The connection risk is low, and the prediction does not affect the importer's constraints.");
		assertThat(recommendation.impact().outcome()).isEqualTo(Outcome.NO_PREDICTED_IMPACT);
		assertThat(recommendation.impact().detail()).isNull();
		assertThat(recommendation.riskLevel()).isEqualTo(Level.LOW);
		assertThat(recommendation.riskExplanation()).isEqualTo(low().explanation());
		assertThat(recommendation.response().wording()).isNotEqualTo(recommendation.riskExplanation());
	}

	@Test
	void eachAssessedLevelSelectsTheRecordedResponse() {
		assertResponse(high(), ShipmentRecommendationImpact.affectsConstraints(), Code.PREPARE_INTERVENTION, "Prepare an intervention");
		assertResponse(medium(), ShipmentRecommendationImpact.affectsConstraints(), Code.REVIEW_PLAN, "Review the plan");
		assertResponse(exactlyTwelveHours(), ShipmentRecommendationImpact.noPredictedImpact(), Code.NOTE_TIMING, "Note the timing");
		assertResponse(exactlyTwelveHours(), ShipmentRecommendationImpact.affectsConstraints(), Code.REVIEW_PLAN, "Review the plan");
		assertResponse(exactlyTwentyFourHours(), ShipmentRecommendationImpact.noPredictedImpact(), Code.NO_ACTION, "No action");
		assertResponse(exactlyTwentyFourHours(), ShipmentRecommendationImpact.affectsConstraints(), Code.MONITOR_CONSTRAINTS, "Monitor");
		assertResponse(low(), ShipmentRecommendationImpact.affectsConstraints(), Code.MONITOR_CONSTRAINTS, "Monitor");
		assertResponse(medium(), ShipmentRecommendationImpact.noPredictedImpact(), Code.NOTE_TIMING, "Note the timing");
		assertResponse(high(), ShipmentRecommendationImpact.noPredictedImpact(), Code.WATCH_CONNECTION, "Watch the connection");
	}

	@Test
	void aMissedWindowAndANonPositivePlannedWindowStayCriticalAndDistinct() {
		ShipmentRisk missed = ShipmentRisk.of(planned(Duration.ofHours(48)), RemainingConnectionWindow.missed("0 seconds", 0));
		ShipmentRisk notPositive = ShipmentRisk.of(windows.calculate(PLANNED, PLANNED), null);
		ShipmentRecommendationImpact impact = ShipmentRecommendationImpact.affectsConstraints();

		ShipmentRecommendation missedRecommendation = recommend(missed, impact);
		ShipmentRecommendation plannedRecommendation = recommend(notPositive, impact);

		assertThat(missed.level()).isEqualTo(Level.CRITICAL);
		assertThat(notPositive.level()).isEqualTo(Level.CRITICAL);
		assertThat(missed.explanation()).isNotEqualTo(notPositive.explanation());
		assertThat(missedRecommendation.response().code()).isEqualTo(Code.ACT_ON_CONNECTION);
		assertThat(plannedRecommendation.response().code()).isEqualTo(Code.ACT_ON_CONNECTION);
		assertThat(missedRecommendation.riskExplanation()).isEqualTo(missed.explanation());
		assertThat(plannedRecommendation.riskExplanation()).isEqualTo(notPositive.explanation());
		assertThat(recommend(missed, ShipmentRecommendationImpact.noPredictedImpact()).response().code())
				.isEqualTo(Code.CONFIRM_CONNECTION);
		assertThat(recommend(notPositive, ShipmentRecommendationImpact.noPredictedImpact()).response().code())
				.isEqualTo(Code.CONFIRM_CONNECTION);
	}

	@Test
	void anUnavailableImpactIsNotNoAction() {
		ShipmentRecommendation notReturned = recommend(low(), ShipmentRecommendationImpact.notReturned());
		ShipmentRecommendation other = recommend(criticalMiss(), ShipmentRecommendationImpact.unavailable(OTHER_IMPACT));

		assertUnavailable(notReturned, Level.LOW, low().explanation(), ShipmentRecommendation.IMPACT_NOT_RETURNED,
				ShipmentRecommendationImpact.NOT_RETURNED);
		assertUnavailable(other, Level.CRITICAL, criticalMiss().explanation(),
				ShipmentRecommendation.IMPACT_UNAVAILABLE + OTHER_IMPACT, OTHER_IMPACT);
		assertThat(notReturned.unavailable()).doesNotContain("No action is needed");
		assertThat(other.response()).isNull();
	}

	@Test
	void aMissingRiskIsUnavailableEvenWhenTheImpactIsComplete() {
		ShipmentRecommendation noImpact = ShipmentRecommendation.of(null, ShipmentRecommendationImpact.noPredictedImpact());
		ShipmentRecommendation affects = ShipmentRecommendation.of(null, ShipmentRecommendationImpact.affectsConstraints());
		ShipmentRecommendation both = ShipmentRecommendation.of(null, ShipmentRecommendationImpact.notReturned());
		ShipmentRecommendation other = ShipmentRecommendation.of(null, ShipmentRecommendationImpact.unavailable(OTHER_IMPACT));

		assertThat(noImpact.available()).isFalse();
		assertThat(noImpact.unavailable()).isEqualTo(ShipmentRecommendation.RISK_NOT_ASSESSED);
		assertThat(noImpact.response()).isNull();
		assertThat(noImpact.riskLevel()).isNull();
		assertThat(noImpact.riskExplanation()).isNull();
		assertThat(noImpact.impact().outcome()).isEqualTo(Outcome.NO_PREDICTED_IMPACT);
		assertThat(affects.unavailable()).isEqualTo(ShipmentRecommendation.RISK_NOT_ASSESSED);
		assertThat(affects.impact().outcome()).isEqualTo(Outcome.AFFECTS_CONSTRAINTS);
		assertThat(both.unavailable()).isEqualTo(ShipmentRecommendation.BOTH_NOT_RETURNED);
		assertThat(both.impact().detail()).isEqualTo(ShipmentRecommendationImpact.NOT_RETURNED);
		assertThat(other.unavailable()).isEqualTo(ShipmentRecommendation.BOTH_UNAVAILABLE + OTHER_IMPACT);
		assertThat(noImpact.unavailable()).doesNotContain("No action is needed");
	}

	@Test
	void theDetailReadUsesTheNotReturnedImpactAndKeepsTheExistingRisk() {
		ConnectionWindowCalculation planned = planned(Duration.ofHours(24));
		ShipmentRisk risk = ShipmentRisk.of(planned, null);
		ShipmentRecommendation recommendation = ShipmentRecommendation.of(risk, ShipmentRecommendationImpact.notReturned());

		assertThat(risk.level()).isEqualTo(Level.LOW);
		assertThat(recommendation.riskLevel()).isEqualTo(risk.level());
		assertThat(recommendation.riskExplanation()).isEqualTo(risk.explanation());
		assertThat(recommendation.available()).isFalse();
		assertThat(recommendation.response()).isNull();
		assertThat(recommendation.impact().outcome()).isEqualTo(Outcome.UNAVAILABLE);
		assertThat(recommendation.impact().detail()).isEqualTo(ShipmentRecommendationImpact.NOT_RETURNED);
	}

	@Test
	void aDisagreementOrAMissingInputIsRejected() {
		ShipmentRecommendationImpact noImpact = ShipmentRecommendationImpact.noPredictedImpact();
		String explanation = low().explanation();
		assertThatThrownBy(() -> new ShipmentRecommendation(true, null,
				ShipmentRecommendedResponse.of(Code.NO_ACTION), Level.HIGH, high().explanation(), noImpact))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShipmentRecommendation(false, "No action is needed.", null, Level.LOW, explanation,
				ShipmentRecommendationImpact.notReturned()))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShipmentRecommendation(true, null, null, Level.LOW, explanation, noImpact))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ShipmentRecommendation.of(low(), null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> ShipmentRecommendationImpact.unavailable(" "))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShipmentRecommendationImpact(Outcome.NO_PREDICTED_IMPACT, "unused"))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ShipmentRecommendedResponse(null, "No action", "No action is needed."))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private void assertResponse(ShipmentRisk risk, ShipmentRecommendationImpact impact, Code code, String label) {
		ShipmentRecommendation recommendation = recommend(risk, impact);
		assertThat(recommendation.available()).isTrue();
		assertThat(recommendation.unavailable()).isNull();
		assertThat(recommendation.response().code()).isEqualTo(code);
		assertThat(recommendation.response().label()).isEqualTo(label);
		assertThat(recommendation.response().wording()).isEqualTo(code.wording());
		if (code != Code.NO_ACTION) {
			assertThat(recommendation.response().wording()).doesNotContain("No action is needed");
		}
		assertThat(recommendation.riskLevel()).isEqualTo(risk.level());
		assertThat(recommendation.riskExplanation()).isEqualTo(risk.explanation());
		assertThat(recommendation.impact()).isEqualTo(impact);
	}

	private static void assertUnavailable(ShipmentRecommendation recommendation, Level level, String explanation,
			String unavailable, String detail) {
		assertThat(recommendation.available()).isFalse();
		assertThat(recommendation.response()).isNull();
		assertThat(recommendation.unavailable()).isEqualTo(unavailable);
		assertThat(recommendation.riskLevel()).isEqualTo(level);
		assertThat(recommendation.riskExplanation()).isEqualTo(explanation);
		assertThat(recommendation.impact().outcome()).isEqualTo(Outcome.UNAVAILABLE);
		assertThat(recommendation.impact().detail()).isEqualTo(detail);
	}

	private ShipmentRecommendation recommend(ShipmentRisk risk, ShipmentRecommendationImpact impact) {
		return ShipmentRecommendation.of(risk, impact);
	}

	private ShipmentRisk low() {
		return remaining(Duration.ofHours(24), Level.LOW);
	}

	private ShipmentRisk exactlyTwentyFourHours() {
		return remaining(Duration.ofHours(24), Level.LOW);
	}

	private ShipmentRisk exactlyTwelveHours() {
		return remaining(Duration.ofHours(12), Level.MEDIUM);
	}

	private ShipmentRisk medium() {
		return remaining(Duration.ofHours(12), Level.MEDIUM);
	}

	private ShipmentRisk high() {
		return remaining(Duration.ofHours(12).minusSeconds(1), Level.HIGH);
	}

	private ShipmentRisk criticalMiss() {
		ShipmentRisk risk = ShipmentRisk.of(planned(Duration.ofHours(48)), RemainingConnectionWindow.missed("2 hours", 7_200));
		assertThat(risk.level()).isEqualTo(Level.CRITICAL);
		return risk;
	}

	private ShipmentRisk remaining(Duration remaining, Level level) {
		ShipmentRisk risk = ShipmentRisk.of(planned(Duration.ofHours(48)),
				RemainingConnectionWindow.available(remaining.toString(), remaining.toSeconds()));
		assertThat(risk.level()).isEqualTo(level);
		return risk;
	}

	private ConnectionWindowCalculation planned(Duration duration) {
		return windows.calculate(PLANNED, PLANNED.plus(duration));
	}
}
