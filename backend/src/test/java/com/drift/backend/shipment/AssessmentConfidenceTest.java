package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ArrivalEstimate.Classification;
import com.drift.backend.shipment.PortCatalog.Coordinates;

class AssessmentConfidenceTest {

	private static final String ESTIMATED = "The estimated arrival was used.";
	private static final String LIMITED = "Confidence is limited because the planned connection window is being used.";
	private static final String MISSING_MOTHER =
			"The assessment is unavailable because the planned mother-vessel arrival is missing.";
	private static final String MISSING_FEEDER =
			"The assessment is unavailable because the planned feeder-vessel departure is missing.";

	private final ConnectionWindowService windows = new ConnectionWindowService();

	@Test
	void trustsTheEstimatedArrivalWhenTheRemainingWindowWasUsed() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = arrival.plusHours(6);
		ArrivalEstimate estimate = usable(arrival, "12.40", Instant.parse("2026-10-15T02:00:00Z"), false);

		AssessmentConfidence confidence = confidence(arrival.minusDays(2), feeder, estimate);

		assertThat(confidence.confidence()).isEqualTo(ESTIMATED);
		assertThat(confidence.unavailable()).isNull();
	}

	@Test
	void keepsTheEstimatedArrivalWhenTheConnectionIsMissed() {
		OffsetDateTime arrival = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		ArrivalEstimate missed = usable(arrival.plusHours(2), "0.50", Instant.parse("2026-10-15T04:00:00Z"), false);
		ArrivalEstimate nothing = usable(arrival, "8", Instant.parse("2026-10-15T01:00:00Z"), false);

		assertThat(confidence(arrival.minusDays(1), arrival, missed).confidence()).isEqualTo(ESTIMATED);
		AssessmentConfidence zero = confidence(arrival.minusHours(12), arrival, nothing);
		assertThat(zero.confidence()).isEqualTo(ESTIMATED);
		assertThat(zero.unavailable()).isNull();
		assertThat(ShipmentRisk.of(windows.calculate(arrival.minusHours(12), arrival), windows.remaining(arrival, nothing))
				.explanation()).isNotEqualTo(ESTIMATED);
	}

	@Test
	void limitsConfidenceWhenTheEstimateCannotBeUsed() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		ArrivalEstimate noPosition = ArrivalEstimate.unavailable("NO_POSITION",
				"No AIS position has been received for the mother vessel.");
		ArrivalEstimate slow = ArrivalEstimate.unavailable("UNUSABLE_SPEED",
				"The mother vessel's speed is missing or below 0.5 knots.");
		ArrivalEstimate outdated = usable(mother.plusHours(2), "11.5", Instant.parse("2026-10-14T20:00:00Z"), true);

		assertLimited(confidence(mother, mother.plusDays(1), noPosition));
		assertLimited(confidence(mother, mother.plusHours(28), slow));
		assertLimited(confidence(mother, mother.plusHours(30), outdated));
		assertLimited(confidence(mother, mother, noPosition));
	}

	@Test
	void namesAMissingPlannedTimeWithoutALimitedConfidence() {
		OffsetDateTime mother = OffsetDateTime.parse("2026-10-15T08:00:00Z");
		OffsetDateTime feeder = OffsetDateTime.parse("2026-10-16T08:00:00Z");

		AssessmentConfidence missingMother = confidence(null, feeder, null);
		assertThat(missingMother.confidence()).isNull();
		assertThat(missingMother.unavailable()).containsExactly(MISSING_MOTHER);

		AssessmentConfidence missingFeeder = confidence(mother, null, null);
		assertThat(missingFeeder.confidence()).isNull();
		assertThat(missingFeeder.unavailable()).containsExactly(MISSING_FEEDER);

		assertThat(confidence(null, null, null).unavailable()).containsExactly(MISSING_MOTHER, MISSING_FEEDER);
	}

	@Test
	void followsTheRecordedSpeedAndFreshnessBoundaries() {
		Instant received = Instant.parse("2026-10-22T00:00:00Z");
		OffsetDateTime plannedArrival = OffsetDateTime.parse("2026-10-22T18:00:00Z");
		OffsetDateTime soon = received.plus(Duration.ofHours(6)).atOffset(ZoneOffset.UTC);
		OffsetDateTime plannedFeeder = plannedArrival.plusHours(6);
		Coordinates port = PortCatalog.load().locate("Singapore").orElseThrow();
		ArrivalEstimateCalculator estimates = new ArrivalEstimateCalculator(PortCatalog.load());

		ArrivalEstimate exactSpeed = estimates.estimate(fix(port, "0.5", received), "Singapore", plannedArrival,
				Clock.fixed(received, ZoneOffset.UTC));
		assertThat(confidence(plannedArrival, soon, exactSpeed).confidence()).isEqualTo(ESTIMATED);

		ArrivalEstimate slow = estimates.estimate(fix(port, "0.49", received), "Singapore", plannedArrival,
				Clock.fixed(received, ZoneOffset.UTC));
		assertThat(slow.unavailableReason().code()).isEqualTo("UNUSABLE_SPEED");
		assertLimited(confidence(plannedArrival, plannedFeeder, slow));

		ArrivalEstimate sixHours = estimates.estimate(fix(port, "12", received), "Singapore", plannedArrival,
				Clock.fixed(received.plus(Duration.ofHours(6)), ZoneOffset.UTC));
		assertThat(sixHours.possiblyOutdated()).isFalse();
		assertThat(confidence(plannedArrival, soon, sixHours).confidence()).isEqualTo(ESTIMATED);

		ArrivalEstimate older = estimates.estimate(fix(port, "12", received), "Singapore", plannedArrival,
				Clock.fixed(received.plus(Duration.ofHours(6)).plusNanos(1), ZoneOffset.UTC));
		assertThat(older.possiblyOutdated()).isTrue();
		assertLimited(confidence(plannedArrival, plannedFeeder, older));
		assertThat(ShipmentRisk.of(windows.calculate(plannedArrival, plannedFeeder), null).explanation())
				.isEqualTo("Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.");
	}

	private static void assertLimited(AssessmentConfidence confidence) {
		assertThat(confidence.confidence()).isEqualTo(LIMITED);
		assertThat(confidence.unavailable()).isNull();
	}

	private AssessmentConfidence confidence(OffsetDateTime mother, OffsetDateTime feeder, ArrivalEstimate estimate) {
		return AssessmentConfidence.of(windows.calculate(mother, feeder), windows.remaining(feeder, estimate), mother, feeder);
	}

	private static ArrivalFix fix(Coordinates port, String knots, Instant receivedAt) {
		return new ArrivalFix(BigDecimal.valueOf(port.latitude()), BigDecimal.valueOf(port.longitude()),
				new BigDecimal(knots), receivedAt);
	}

	private static ArrivalEstimate usable(OffsetDateTime estimatedArrival, String knots, Instant receivedAt,
			boolean possiblyOutdated) {
		return ArrivalEstimate.available(estimatedArrival, new BigDecimal(knots), Classification.BEHIND_PLAN,
				"Behind plan · estimated 2h later than planned", 7_200, 0, receivedAt, possiblyOutdated);
	}
}
