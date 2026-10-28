package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.drift.backend.shipment.ArrivalEstimate.Classification;

/** CDG-209. The worked examples in docs/cdg42-miss-probability-decision.md, with a fixed estimated arrival. */
class LateArrivalChanceTest {

	private static final double TOLERANCE = 1e-3;

	private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
	private static final Instant RECEIVED = Instant.parse("2026-10-26T11:00:00Z");
	/** Base scenario: 720 nautical miles at 15 knots, so 48 hours after the position was received. */
	private static final OffsetDateTime BASE_ARRIVAL = OffsetDateTime.parse("2026-10-28T11:00:00Z");
	/** Short-distance scenario: 1 nautical mile at 0.5 knots, so 2 hours after the position was received. */
	private static final OffsetDateTime SHORT_ARRIVAL = OffsetDateTime.parse("2026-10-26T13:00:00Z");
	private static final OffsetDateTime PLANNED = OffsetDateTime.parse("2026-10-28T09:00:00Z");
	private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-10-28T13:00:00Z");

	private final ConnectionWindowService windows = new ConnectionWindowService();

	record Row(int number, Instant now, ArrivalEstimate estimate, OffsetDateTime planned, OffsetDateTime departure,
			Double sigma, Double probability, String display, String code, Boolean plannedWindowInvalid) {
		@Override
		public String toString() {
			return "row " + number;
		}
	}

	static Stream<Row> workedExamples() {
		ArrivalEstimate base = fresh(BASE_ARRIVAL);
		ArrivalEstimate shortDistance = fresh(SHORT_ARRIVAL);
		return Stream.of(
				shown(1, NOW, base, PLANNED, at("2026-10-30T11:00:00Z"), 5.206, 0.0000, "<5%", false),
				shown(2, NOW, base, PLANNED, at("2026-10-28T23:00:00Z"), 5.206, 0.0106, "<5%", false),
				shown(3, NOW, base, PLANNED, DEPARTURE, 5.206, 0.3504, "35%", false),
				shown(4, NOW, base, PLANNED, at("2026-10-28T11:00:00Z"), 5.206, 0.5000, "50%", false),
				shown(5, NOW, base, at("2026-10-28T04:00:00Z"), at("2026-10-28T08:00:00Z"), 5.206, 0.7178, "70%", false),
				shown(6, NOW, base, at("2026-10-27T21:00:00Z"), at("2026-10-28T01:00:00Z"), 5.206, 0.9726, "95%", false),
				shown(7, NOW, shortDistance, at("2026-10-26T13:00:00Z"), at("2026-10-26T15:00:00Z"), 2.025, 0.1617,
						"15%", false),
				shown(8, NOW, fresh(at("2026-12-25T11:00:00Z")), PLANNED, DEPARTURE, 144.014, 1.0000, ">95%", false),
				unavailable(9, NOW, ArrivalEstimate.unavailable(ArrivalEstimateCalculator.UNUSABLE_SPEED,
						"The mother vessel's speed is missing or below 0.5 knots."), PLANNED, DEPARTURE, "UNUSABLE_SPEED"),
				shown(10, Instant.parse("2026-10-26T17:00:00Z"), base, PLANNED, DEPARTURE, 5.412, 0.3559, "35%", false),
				unavailable(11, Instant.parse("2026-10-26T17:00:01Z"), estimate(BASE_ARRIVAL, true), PLANNED, DEPARTURE,
						"POSITION_OUTDATED"),
				unavailable(12, NOW, base, PLANNED, null, "MISSING_FEEDER_DEPARTURE"),
				unavailable(13, NOW, null, null, DEPARTURE, "MISSING_PLANNED_MOTHER_ARRIVAL"),
				unavailable(14, NOW, null, null, null, "MISSING_PLANNED_MOTHER_ARRIVAL"),
				unavailable(15, NOW, ArrivalEstimate.unavailable(ArrivalEstimateCalculator.NO_POSITION,
						"No AIS position has been received for the mother vessel."), PLANNED, DEPARTURE, "NO_POSITION"),
				shown(16, NOW, base, at("2026-10-29T00:00:00Z"), at("2026-10-28T23:00:00Z"), 5.206, 0.0106, "<5%", true),
				shown(17, NOW, shortDistance, at("2026-10-26T11:00:00Z"), at("2026-10-26T12:00:00Z"), 2.025, 0.6892,
						"70%", false),
				unavailable(18, NOW, shortDistance, at("2026-10-26T11:00:00Z"), at("2026-10-26T11:59:59Z"),
						"FEEDER_DEPARTURE_PASSED"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("workedExamples")
	void matchesTheWorkedExample(Row row) {
		ConnectionWindowCalculation planned = windows.calculate(row.planned(), row.departure());
		LateArrivalChance chance = LateArrivalChance.of(row.estimate(), row.planned(), row.departure(), planned,
				row.now());

		if (row.code() != null) {
			assertThat(chance.available()).isFalse();
			assertThat(chance.unavailableReason().code()).isEqualTo(row.code());
			assertThat(chance.unavailableReason().message()).startsWith(LateArrivalChance.UNAVAILABLE_PREFIX);
			assertThat(chance.probability()).isNull();
			assertThat(chance.display()).isNull();
			assertThat(chance.label()).isNull();
			assertThat(chance.arrivalUsed()).isNull();
			assertThat(chance.plannedFeederDepartureUsed()).isNull();
			assertThat(chance.plannedWindowInvalid()).isNull();
			return;
		}
		assertThat(chance.available()).isTrue();
		assertThat(chance.unavailableReason()).isNull();
		assertThat(chance.probability()).isCloseTo(row.probability(), within(TOLERANCE));
		assertThat(chance.display()).isEqualTo(row.display());
		assertThat(chance.label())
				.isEqualTo("Chance of arriving after feeder departure: " + row.display() + " · rough estimate");
		assertThat(chance.plannedWindowInvalid()).isEqualTo(row.plannedWindowInvalid());
		assertThat(chance.arrivalUsed()).isEqualTo(row.estimate().estimatedArrivalAt());
		assertThat(chance.plannedFeederDepartureUsed()).isEqualTo(row.departure());
		assertThat(new StraightLineNormalArrival(row.estimate(), row.now()).spreadHours())
				.isCloseTo(row.sigma(), within(TOLERANCE));
	}

	@Test
	void spreadMeasuresTheVoyageFromTheReceivedPositionAndNotFromNow() {
		// τ = 48h from t₀ gives 5.206h; measuring from now (47h) would give 5.114h.
		assertThat(new StraightLineNormalArrival(fresh(BASE_ARRIVAL), NOW).spreadHours())
				.isCloseTo(5.206, within(TOLERANCE))
				.isNotCloseTo(5.114, within(TOLERANCE));
	}

	@Test
	void standardNormalCdfMatchesKnownValues() {
		assertThat(StraightLineNormalArrival.standardNormalCdf(0)).isCloseTo(0.5, within(1e-7));
		assertThat(StraightLineNormalArrival.standardNormalCdf(1.96)).isCloseTo(0.9750021, within(1e-6));
		assertThat(StraightLineNormalArrival.standardNormalCdf(-1.96)).isCloseTo(0.0249979, within(1e-6));
		assertThat(StraightLineNormalArrival.standardNormalCdf(1)).isCloseTo(0.8413447, within(1e-6));
		assertThat(StraightLineNormalArrival.standardNormalCdf(-2.5)).isCloseTo(0.0062097, within(1e-6));
	}

	@Test
	void standardNormalCdfIsSymmetric() {
		// Exact for z ≠ 0 by the odd extension of erf. At z = 0, A–S gives erf(0) = 1e-9, inside its error bound.
		for (double z : new double[] { 0.1, 0.5, 1, 1.96, 3, 5, 8, 12 }) {
			assertThat(StraightLineNormalArrival.standardNormalCdf(z) + StraightLineNormalArrival.standardNormalCdf(-z))
					.as("Φ(%s) + Φ(−%s)", z, z)
					.isCloseTo(1.0, within(1e-12));
		}
	}

	@Test
	void standardNormalCdfIsClampedToEightStandardDeviations() {
		assertThat(StraightLineNormalArrival.standardNormalCdf(50)).isEqualTo(StraightLineNormalArrival.standardNormalCdf(8));
		assertThat(StraightLineNormalArrival.standardNormalCdf(Double.NEGATIVE_INFINITY))
				.isEqualTo(StraightLineNormalArrival.standardNormalCdf(-8));
		assertThat(StraightLineNormalArrival.standardNormalCdf(-8)).isBetween(0.0, 1e-12);
	}

	@Test
	void displayRoundsHalfUpToFivePercentAndNeverShowsZeroOrHundred() {
		assertThat(LateArrivalChance.display(0.0)).isEqualTo("<5%");
		assertThat(LateArrivalChance.display(0.0249)).isEqualTo("<5%");
		assertThat(LateArrivalChance.display(0.025)).isEqualTo("5%");
		assertThat(LateArrivalChance.display(0.3504)).isEqualTo("35%");
		assertThat(LateArrivalChance.display(0.974)).isEqualTo("95%");
		assertThat(LateArrivalChance.display(0.975)).isEqualTo(">95%");
		assertThat(LateArrivalChance.display(1.0)).isEqualTo(">95%");
	}

	@Test
	void everyUnavailableReasonUsesTheRecordedWording() {
		String prefix = "Chance of arriving after feeder departure unavailable: ";
		assertReason(null, null, DEPARTURE, NOW, "MISSING_PLANNED_MOTHER_ARRIVAL",
				prefix + "the planned mother-vessel arrival is not set, so no arrival estimate exists.");
		assertReason(fresh(BASE_ARRIVAL), PLANNED, null, NOW, "MISSING_FEEDER_DEPARTURE",
				prefix + "the planned feeder departure is not set.");
		assertReason(fresh(BASE_ARRIVAL), PLANNED, DEPARTURE, DEPARTURE.toInstant().plusSeconds(1),
				"FEEDER_DEPARTURE_PASSED",
				prefix + "the planned feeder departure has already passed, and DRIFT cannot confirm whether the connection was made.");
		assertEstimateReason(ArrivalEstimateCalculator.NO_POSITION,
				prefix + "no position has been received for the mother vessel.");
		assertEstimateReason(ArrivalEstimateCalculator.INVALID_COORDINATES,
				prefix + "the vessel or port position cannot be used.");
		assertEstimateReason(ArrivalEstimateCalculator.MISSING_PORT,
				prefix + "the transshipment port is not set for this shipment.");
		assertEstimateReason(ArrivalEstimateCalculator.UNKNOWN_PORT,
				prefix + "the transshipment port is not on the route map.");
		assertEstimateReason(ArrivalEstimateCalculator.UNUSABLE_SPEED,
				prefix + "the vessel's speed is missing or below 0.5 knots, so arrival cannot be estimated.");
		assertReason(estimate(BASE_ARRIVAL, true), PLANNED, DEPARTURE, NOW, "POSITION_OUTDATED",
				prefix + "the latest vessel position is more than 6 hours old.");
	}

	@Test
	void aPassedDepartureIsNamedBeforeTheEstimateReason() {
		ArrivalEstimate noPosition = ArrivalEstimate.unavailable(ArrivalEstimateCalculator.NO_POSITION, "No position.");
		assertThat(LateArrivalChance.of(noPosition, PLANNED, DEPARTURE, windows.calculate(PLANNED, DEPARTURE),
				DEPARTURE.toInstant().plusSeconds(1)).unavailableReason().code()).isEqualTo("FEEDER_DEPARTURE_PASSED");
		assertThat(LateArrivalChance.of(noPosition, PLANNED, null, windows.calculate(PLANNED, null), NOW)
				.unavailableReason().code()).isEqualTo("MISSING_FEEDER_DEPARTURE");
	}

	@Test
	void anUnknownEstimateReasonKeepsItsCodeAndUsesTheGeneralWording() {
		// The list and risk evaluation build the same snapshot, so a new estimate code must not fail the read.
		assertReason(ArrivalEstimate.unavailable("SOMETHING_ELSE", "Something else."), PLANNED, DEPARTURE, NOW,
				"SOMETHING_ELSE", LateArrivalChance.UNAVAILABLE_PREFIX + "the arrival estimate cannot be used.");
	}

	@Test
	void theRecordKeepsAvailableAndUnavailableApart() {
		ArrivalEstimate.UnavailableReason reason = new ArrivalEstimate.UnavailableReason("NO_POSITION",
				LateArrivalChance.UNAVAILABLE_PREFIX + "no position has been received for the mother vessel.");
		assertThatThrownBy(() -> new LateArrivalChance(true, 0.35, "35%",
				"Chance of arriving after feeder departure: 35% · rough estimate", BASE_ARRIVAL, DEPARTURE, false, reason))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LateArrivalChance(true, null, "35%",
				"Chance of arriving after feeder departure: 35% · rough estimate", BASE_ARRIVAL, DEPARTURE, false, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LateArrivalChance(true, 0.35, "0%",
				"Chance of arriving after feeder departure: 0% · rough estimate", BASE_ARRIVAL, DEPARTURE, false, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LateArrivalChance(true, 0.35, "35%", "Chance of arriving after feeder departure: 35%",
				BASE_ARRIVAL, DEPARTURE, false, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LateArrivalChance(false, 0.35, null, null, null, null, null, reason))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LateArrivalChance(false, null, null, null, null, null, false, reason))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LateArrivalChance(false, null, null, null, null, null, null, null))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theDistributionOnlyAcceptsAFreshAvailableEstimate() {
		assertThatThrownBy(() -> new StraightLineNormalArrival(estimate(BASE_ARRIVAL, true), NOW))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new StraightLineNormalArrival(
				ArrivalEstimate.unavailable(ArrivalEstimateCalculator.NO_POSITION, "No position."), NOW))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private void assertEstimateReason(String code, String message) {
		assertReason(ArrivalEstimate.unavailable(code, "Estimate wording."), PLANNED, DEPARTURE, NOW, code, message);
	}

	private void assertReason(ArrivalEstimate estimate, OffsetDateTime planned, OffsetDateTime departure, Instant now,
			String code, String message) {
		LateArrivalChance chance = LateArrivalChance.of(estimate, planned, departure,
				windows.calculate(planned, departure), now);
		assertThat(chance.available()).isFalse();
		assertThat(chance.unavailableReason().code()).isEqualTo(code);
		assertThat(chance.unavailableReason().message()).isEqualTo(message);
	}

	private static Row shown(int number, Instant now, ArrivalEstimate estimate, OffsetDateTime planned,
			OffsetDateTime departure, double sigma, double probability, String display, boolean plannedWindowInvalid) {
		return new Row(number, now, estimate, planned, departure, sigma, probability, display, null,
				plannedWindowInvalid);
	}

	private static Row unavailable(int number, Instant now, ArrivalEstimate estimate, OffsetDateTime planned,
			OffsetDateTime departure, String code) {
		return new Row(number, now, estimate, planned, departure, null, null, null, code, null);
	}

	private static ArrivalEstimate fresh(OffsetDateTime estimatedArrivalAt) {
		return estimate(estimatedArrivalAt, false);
	}

	/** The comparison fields do not enter the chance, so they are fixed placeholders here. */
	private static ArrivalEstimate estimate(OffsetDateTime estimatedArrivalAt, boolean possiblyOutdated) {
		return ArrivalEstimate.available(estimatedArrivalAt, new BigDecimal("15"), Classification.ON_SCHEDULE,
				"On schedule · estimated at the planned time", 0, 0, RECEIVED, possiblyOutdated);
	}

	private static OffsetDateTime at(String instant) {
		return OffsetDateTime.parse(instant);
	}
}
