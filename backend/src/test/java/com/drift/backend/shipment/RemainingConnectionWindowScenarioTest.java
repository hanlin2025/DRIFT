package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ConnectionWindowCalculation.Status;
import com.drift.backend.shipment.PortCatalog.Coordinates;
import com.drift.backend.shipment.ShipmentRisk.Level;

class RemainingConnectionWindowScenarioTest {

	private static final Instant RECEIVED = Instant.parse("2026-10-22T00:00:00Z");
	private static final OffsetDateTime PLANNED = OffsetDateTime.parse("2026-10-22T18:00:00Z");

	private final ConnectionWindowService windows = new ConnectionWindowService();
	private final ArrivalEstimateCalculator estimates = new ArrivalEstimateCalculator(PortCatalog.load());

	@Test
	void usesAnEstimateThatIsExactlySixHoursOldAndFallsBackOneNanosecondLater() {
		ArrivalEstimate exact = estimate(Clock.fixed(RECEIVED.plus(Duration.ofHours(6)), ZoneOffset.UTC));
		assertThat(exact.possiblyOutdated()).isFalse();
		assertThat(exact.lastReceivedAt()).isEqualTo(RECEIVED);
		assertThat(exact.estimatedArrivalAt().toInstant()).isEqualTo(RECEIVED);

		RemainingConnectionWindow remaining = windows.remaining(RECEIVED.plus(Duration.ofHours(6)).atOffset(ZoneOffset.UTC), exact);
		assertThat(remaining.status()).isEqualTo(RemainingConnectionWindow.Status.AVAILABLE);
		assertThat(remaining.duration()).isEqualTo("6 hours");
		assertThat(ShipmentRisk.of(planned(Duration.ofHours(8)), remaining).explanation())
				.isEqualTo("Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival.");

		ArrivalEstimate overdue = estimate(Clock.fixed(RECEIVED.plus(Duration.ofHours(6)).plusNanos(1), ZoneOffset.UTC));
		assertThat(overdue.possiblyOutdated()).isTrue();
		assertThat(overdue.lastReceivedAt()).isEqualTo(RECEIVED);
		assertThat(windows.remaining(PLANNED, overdue)).isNull();
		assertThat(ShipmentRisk.of(planned(Duration.ofHours(6)), null).explanation())
				.isEqualTo("Less than 12 hours to transfer cargo between vessels. The planned connection window is being used.");
	}

	@Test
	void classifiesTheWindowThatWasUsedAtEachRiskBoundary() {
		ArrivalEstimate fresh = estimate(Clock.fixed(RECEIVED, ZoneOffset.UTC));
		assertThat(risk(fresh, Duration.ofHours(12).minusSeconds(1)))
				.isEqualTo(new ShipmentRisk(Level.HIGH, "Less than 12 hours remain to transfer cargo, based on the mother vessel's estimated arrival."));
		assertThat(risk(fresh, Duration.ofHours(12)))
				.isEqualTo(new ShipmentRisk(Level.MEDIUM, "Less than 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival."));
		assertThat(risk(fresh, Duration.ofHours(24)))
				.isEqualTo(new ShipmentRisk(Level.LOW, "At least 24 hours remain to transfer cargo, based on the mother vessel's estimated arrival."));

		RemainingConnectionWindow missed = windows.remaining(RECEIVED.minus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC), fresh);
		assertThat(missed.missedBy()).isEqualTo("2 hours");
		assertThat(ShipmentRisk.of(planned(Duration.ofHours(1)), missed))
				.isEqualTo(new ShipmentRisk(Level.CRITICAL, "The connection is expected to be missed by 2 hours."));

		RemainingConnectionWindow brief = windows.remaining(RECEIVED.plusMillis(500).atOffset(ZoneOffset.UTC), fresh);
		assertThat(brief.duration()).isEqualTo("less than 1 second");
		assertThat(brief.totalSeconds()).isZero();
	}

	@Test
	void anUnavailableEstimateAndAMissingFeederDepartureHaveNoRemainingWindow() {
		OffsetDateTime departure = RECEIVED.plus(Duration.ofHours(6)).atOffset(ZoneOffset.UTC);
		for (String code : new String[] { "NO_POSITION", "INVALID_COORDINATES", "MISSING_PORT", "UNKNOWN_PORT", "UNUSABLE_SPEED" }) {
			ArrivalEstimate unavailable = ArrivalEstimate.unavailable(code, "The planned window is the fallback.");
			assertThat(windows.remaining(departure, unavailable)).isNull();
		}
		assertThat(windows.remaining(null, estimate(Clock.fixed(RECEIVED, ZoneOffset.UTC)))).isNull();
		assertThat(ShipmentRisk.of(ConnectionWindowCalculation.incomplete(), null)).isNull();
		assertThat(ShipmentRisk.of(ConnectionWindowCalculation.invalid(), null).explanation())
				.isEqualTo("The feeder vessel is planned to depart at or before the mother vessel's arrival, leaving no time to transfer cargo. The planned connection window is being used.");
	}

	private ShipmentRisk risk(ArrivalEstimate estimate, Duration afterEstimate) {
		RemainingConnectionWindow remaining = windows.remaining(RECEIVED.plus(afterEstimate).atOffset(ZoneOffset.UTC), estimate);
		return ShipmentRisk.of(planned(Duration.ofHours(1)), remaining);
	}

	private ConnectionWindowCalculation planned(Duration duration) {
		ConnectionWindowCalculation calculation = windows.calculate(PLANNED, PLANNED.plus(duration));
		assertThat(calculation.status()).isEqualTo(Status.AVAILABLE);
		return calculation;
	}

	private ArrivalEstimate estimate(Clock clock) {
		Coordinates port = PortCatalog.load().locate("Singapore").orElseThrow();
		ArrivalFix fix = new ArrivalFix(BigDecimal.valueOf(port.latitude()), BigDecimal.valueOf(port.longitude()),
				new BigDecimal("12"), RECEIVED);
		return estimates.estimate(fix, "Singapore", PLANNED, clock);
	}
}
