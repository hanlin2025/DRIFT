package com.drift.backend.shipment;

import static com.drift.backend.shipment.ShipmentDelayAttributionTest.estimate;
import static com.drift.backend.shipment.ShipmentDelayAttributionTest.eta;
import static com.drift.backend.shipment.ShipmentDelayAttributionTest.notice;
import static com.drift.backend.shipment.ShipmentDelayAttributionTest.weather;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.maritimechange.MaritimeSignal;
import com.drift.backend.shipment.ShipmentRisk.Level;

class ShipmentRiskAdjustmentTest {

	private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-11-03T08:00:00Z");
	private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-11-03T20:00:00Z");

	@Test
	void aVerifiedMpaDelayRaisesLowToMedium() {
		ShipmentRisk adjusted = ShipmentRisk.adjusted(risk(Level.LOW), attribution(null,
				eta("VERIFIED_ASSOCIATION", "MOTHER", ARRIVAL.plusHours(3).toInstant(), null)));

		assertThat(adjusted.level()).isEqualTo(Level.MEDIUM);
		assertThat(adjusted.explanation()).isEqualTo("Base. Raised from Low because MPA's expected arrival for this shipment's vessel supports a delay.");
	}

	@Test
	void portDataCorroboratedByAisRaisesMediumToHigh() {
		ShipmentRisk adjusted = ShipmentRisk.adjusted(risk(Level.MEDIUM),
				attribution(estimate(ArrivalEstimate.Classification.BEHIND_PLAN), notice("Port congestion at Tuas")));

		assertThat(adjusted.level()).isEqualTo(Level.HIGH);
		assertThat(adjusted.explanation()).endsWith("because MPA port data and another independent source both support a delay.");
	}

	@Test
	void highAndCriticalAreNotRaised() {
		ShipmentDelayAttribution delay = attribution(null,
				eta("VERIFIED_ASSOCIATION", "MOTHER", ARRIVAL.plusHours(3).toInstant(), null));

		assertThat(ShipmentRisk.adjusted(risk(Level.HIGH), delay)).isEqualTo(risk(Level.HIGH));
		assertThat(ShipmentRisk.adjusted(risk(Level.CRITICAL), delay)).isEqualTo(risk(Level.CRITICAL));
	}

	@Test
	void weatherAloneOrWithAisDoesNotRaiseTheLevel() {
		assertThat(ShipmentRisk.adjusted(risk(Level.LOW), attribution(null, weather("Thundery Showers"))).level())
				.isEqualTo(Level.LOW);
		assertThat(ShipmentRisk.adjusted(risk(Level.LOW),
				attribution(estimate(ArrivalEstimate.Classification.BEHIND_PLAN), weather("Thundery Showers"))).level())
				.isEqualTo(Level.LOW);
	}

	@Test
	void anUnverifiedMpaArrivalOrAnUnsupportedNoticeDoesNotRaiseTheLevel() {
		assertThat(ShipmentRisk.adjusted(risk(Level.LOW), attribution(null,
				eta("UNVERIFIED_IMO_ONLY", "MOTHER", ARRIVAL.plusHours(3).toInstant(), null))).level()).isEqualTo(Level.LOW);
		assertThat(ShipmentRisk.adjusted(risk(Level.LOW), attribution(null, notice("Port congestion at Tuas"))).level())
				.isEqualTo(Level.LOW);
	}

	@Test
	void aMissingRiskStaysMissing() {
		assertThat(ShipmentRisk.adjusted(null, ShipmentDelayAttribution.unavailable())).isNull();
	}

	@Test
	void theExplanationAddsSupportingExternalSignalsAndCorroboration() {
		ShipmentDelayAttribution attribution = attribution(estimate(ArrivalEstimate.Classification.BEHIND_PLAN),
				notice("Port congestion at Tuas"));
		AssessmentExplanation base = new AssessmentExplanation(List.of("Remaining connection window"), List.of("Rule"),
				"The estimated arrival was used.", null);

		AssessmentExplanation explained = AssessmentExplanation.withAttribution(base, attribution);

		assertThat(explained.evidence()).containsExactly("Remaining connection window",
				"MPA notice for Tuas, Singapore: Port congestion at Tuas");
		assertThat(explained.assumptions()).containsExactly("Rule", AssessmentExplanation.ATTRIBUTION_RULE);
		assertThat(explained.confidence()).isEqualTo("The estimated arrival was used. Corroborated by 2 independent sources.");
	}

	@Test
	void theExplanationIsUnchangedWithoutAnExternalSignal() {
		AssessmentExplanation base = new AssessmentExplanation(List.of("Remaining connection window"), List.of("Rule"),
				"The estimated arrival was used.", null);

		assertThat(AssessmentExplanation.withAttribution(base,
				attribution(estimate(ArrivalEstimate.Classification.BEHIND_PLAN)))).isSameAs(base);
	}

	private static ShipmentRisk risk(Level level) {
		return new ShipmentRisk(level, "Base.");
	}

	private static ShipmentDelayAttribution attribution(ArrivalEstimate estimate, MaritimeSignal... signals) {
		return ShipmentDelayAttribution.of(estimate, List.of(signals), ARRIVAL, DEPARTURE,
				ShipmentDelayAttributionProperties.defaults());
	}
}
