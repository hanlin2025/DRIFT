package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.ais.operation.OperationalState;
import com.drift.backend.ais.operation.OperationalStateService;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;

/** CDG-276 records this rule. CDG-277 returns it. The comparison is not a congestion index. */
class PortConditionComparisonTest {

	private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
	private static final BigDecimal SINGAPORE_LATITUDE = new BigDecimal("1.264");
	private static final BigDecimal SINGAPORE_LONGITUDE = new BigDecimal("103.840");
	private static final BigDecimal PELEPAS_LATITUDE = new BigDecimal("1.362");
	private static final BigDecimal PELEPAS_LONGITUDE = new BigDecimal("103.549");
	private static final BigDecimal FAR_LATITUDE = new BigDecimal("1.698");

	private final OperationalStateService ports = new OperationalStateService();

	@Test
	void matchesWhenTheCurrentSpeedIsAtLeastTheSlowestOwnReportNearThePort() {
		PortConditionComparison match = compare("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8.0", 0, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "4.0", 0, hoursAgo(3)),
						report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "6.0", 0, hoursAgo(4))));
		PortConditionComparison equal = compare("MV Ever Steady", "Port of Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "4.00", 0, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "4.0", 0, hoursAgo(3))));

		assertThat(match.outcome()).isEqualTo(PortConditionComparison.Outcome.MATCHES_NORMAL);
		assertThat(match.reason()).isNull();
		assertThat(match.message()).isEqualTo(PortConditionComparison.MATCHES);
		assertThat(match.vessel()).isEqualTo(PortConditionComparison.ComparedVessel.MOTHER);
		assertThat(match.knownPort()).isEqualTo("Singapore");
		assertThat(match.earlierReportCount()).isEqualTo(2);
		assertThat(match.slowestOwnSpeedKnots()).isEqualByComparingTo("4.0");
		assertThat(match.message()).doesNotContain("Live", "Delayed", "Offline");
		assertThat(equal.outcome()).isEqualTo(PortConditionComparison.Outcome.MATCHES_NORMAL);
		assertThat(equal.transshipmentPort()).isEqualTo("Port of Singapore");
		assertThat(equal.knownPort()).isEqualTo("Singapore");
	}

	@Test
	void reportsASlowerMovementWithoutCallingItCongestion() {
		PortConditionComparison slower = compare("MV Ever Steady", "SGSIN",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "3.9", 0, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "4.0", 0, hoursAgo(3)),
						report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "9.0", 5, hoursAgo(5))));

		assertThat(slower.outcome()).isEqualTo(PortConditionComparison.Outcome.SLOWER_THAN_OWN_REPORTS);
		assertThat(slower.reason()).isNull();
		assertThat(slower.message()).isEqualTo(PortConditionComparison.SLOWER);
		assertThat(slower.message()).doesNotContain("congested");
		assertThat(slower.slowestOwnSpeedKnots()).isEqualByComparingTo("4.0");
		assertThat(slower.earlierReportCount()).isEqualTo(2);
		assertThat(slower.message()).isNotEqualTo(state(slower).description());
	}

	@Test
	void usesTanjungPelepasAndIgnoresAReportThatIsOnlyNearSingapore() {
		// The two reference points are about 18 nautical miles apart, so a fix on one can be inside
		// the other's 25 nautical mile circle. This fix is east of Singapore and outside Pelepas.
		BigDecimal onlySingapore = new BigDecimal("104.140");
		PortConditionComparison comparison = compare("MV Ever Steady", "MYTPP",
				report(PELEPAS_LATITUDE, PELEPAS_LONGITUDE, "7", 0, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, onlySingapore, "1", 0, hoursAgo(3)),
						report(PELEPAS_LATITUDE, PELEPAS_LONGITUDE, "7", 0, hoursAgo(4), AisPositionSource.SEEDED)));

		assertThat(comparison.outcome()).isEqualTo(PortConditionComparison.Outcome.MATCHES_NORMAL);
		assertThat(comparison.knownPort()).isEqualTo("Tanjung Pelepas");
		assertThat(comparison.earlierReportCount()).isEqualTo(1);
		assertThat(comparison.slowestOwnSpeedKnots()).isEqualByComparingTo("7");
		assertThat(comparison.source()).isEqualTo(AisPositionSource.AIS_STREAM);
		assertThat(ports.nearKnownPort(SINGAPORE_LATITUDE, onlySingapore, "Singapore")).isTrue();
		assertThat(ports.nearKnownPort(SINGAPORE_LATITUDE, onlySingapore, "Tanjung Pelepas")).isFalse();
	}

	@Test
	void keepsASpeedBelowTheEstimateCutoffAndBelowTheTrackSlowdown() {
		assertThat(new BigDecimal("0.4").compareTo(ArrivalEstimateCalculator.MINIMUM_SPEED_KNOTS)).isNegative();
		assertThat(new BigDecimal("2.5").compareTo(VesselTrackService.SLOWDOWN_THRESHOLD_KNOTS)).isNegative();

		PortConditionComparison slow = compare("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "0.4", 0, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "0.4", 0, hoursAgo(3))));
		PortConditionComparison belowTrack = compare("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "2.5", 0, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "2.0", 0, hoursAgo(3))));

		assertThat(slow.outcome()).isEqualTo(PortConditionComparison.Outcome.MATCHES_NORMAL);
		assertThat(belowTrack.outcome()).isEqualTo(PortConditionComparison.Outcome.MATCHES_NORMAL);
	}

	@Test
	void usesAPositionThatIsExactlySixHoursOldAndRejectsOneNanosecondOlder() {
		PortConditionComparison exact = compare("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8", 0, NOW.minus(ArrivalEstimateCalculator.OUTDATED_AFTER)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8", 0, hoursAgo(8))));
		PortConditionComparison older = compare("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8", 0,
						NOW.minus(ArrivalEstimateCalculator.OUTDATED_AFTER).minusNanos(1)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8", 0, hoursAgo(8))));

		assertThat(exact.outcome()).isEqualTo(PortConditionComparison.Outcome.MATCHES_NORMAL);
		assertThat(older.reason()).isEqualTo(PortConditionComparison.Reason.POSITION_TOO_OLD);
		assertThat(older.message()).isEqualTo(PortConditionComparison.POSITION_TOO_OLD_MESSAGE);
		assertThat(older.outcome()).isNull();
		assertThat(older.earlierReportCount()).isZero();
	}

	@Test
	void treatsExactlyOneKnotAsReliableAndAStoppedStatusAboveItAsUnusable() {
		PortConditionComparison exact = compare("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "1.0", 5, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "1.0", 5, hoursAgo(3))));
		PortConditionComparison moored = compare("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "1.1", 5, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "0.2", 5, hoursAgo(3))));
		PortConditionComparison anchored = compare("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "2", 1, minutesAgo(2)), List.of());
		PortConditionComparison aground = compare("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "3", 6, minutesAgo(2)), List.of());

		assertThat(exact.outcome()).isEqualTo(PortConditionComparison.Outcome.MATCHES_NORMAL);
		assertThat(state(exact).unreliable()).isFalse();
		assertThat(moored.reason()).isEqualTo(PortConditionComparison.Reason.UNRELIABLE_REPORT);
		assertThat(moored.message()).isEqualTo(PortConditionComparison.UNRELIABLE_REPORT_MESSAGE);
		assertThat(state(moored).unreliable()).isTrue();
		assertThat(state(moored).description()).isEqualTo("Moored near Singapore");
		assertThat(moored.message()).isNotEqualTo(state(moored).description());
		assertThat(anchored.reason()).isEqualTo(PortConditionComparison.Reason.UNRELIABLE_REPORT);
		assertThat(aground.reason()).isEqualTo(PortConditionComparison.Reason.UNRELIABLE_REPORT);
	}

	@Test
	void namesEachWayTheComparisonStaysUnavailable() {
		assertThat(compare("MV Ever Steady", "Singapore", null, List.of()).reason())
				.isEqualTo(PortConditionComparison.Reason.NO_REPORT);
		assertThat(compare("MV Ever Steady", "Singapore", null, List.of()).message())
				.isEqualTo(PortConditionComparison.NO_REPORT_MESSAGE);
		assertThat(compare("MV Ever Steady", "  ", report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8", 0, minutesAgo(2)),
				List.of()).reason()).isEqualTo(PortConditionComparison.Reason.MISSING_PORT);
		PortConditionComparison otherPort = compare("MV Ever Steady", "Jakarta",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8", 0, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "4", 0, hoursAgo(3))));
		assertThat(otherPort.reason()).isEqualTo(PortConditionComparison.Reason.PORT_NOT_DEFINED);
		assertThat(otherPort.message()).isEqualTo(PortConditionComparison.PORT_NOT_DEFINED_MESSAGE);
		assertThat(otherPort.knownPort()).isNull();
		assertThat(state(otherPort).description()).isEqualTo(state(otherPort).label());
		assertThat(compare("MV Ever Steady", "Singapore",
				report(new BigDecimal("91"), SINGAPORE_LONGITUDE, "8", 0, minutesAgo(2)), List.of()).reason())
				.isEqualTo(PortConditionComparison.Reason.UNUSABLE_POSITION);
		PortConditionComparison far = compare("MV Ever Steady", "Singapore",
				report(FAR_LATITUDE, SINGAPORE_LONGITUDE, "8", 0, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "1", 0, hoursAgo(3))));
		assertThat(far.reason()).isEqualTo(PortConditionComparison.Reason.NOT_NEAR_PORT);
		assertThat(far.message()).isEqualTo(PortConditionComparison.NOT_NEAR_PORT_MESSAGE);
		assertThat(state(far).description()).endsWith(", far from port");
		assertThat(compare("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, null, 0, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "4", 0, hoursAgo(3)))).reason())
				.isEqualTo(PortConditionComparison.Reason.MISSING_SPEED);
		PortConditionComparison noBaseline = compare("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8", 0, minutesAgo(2)), List.of());
		assertThat(noBaseline.reason()).isEqualTo(PortConditionComparison.Reason.NO_BASELINE);
		assertThat(noBaseline.message()).isEqualTo(PortConditionComparison.NO_BASELINE_MESSAGE);
		assertThat(noBaseline.message()).isNotEqualTo(PortConditionComparison.MATCHES);
		assertThat(noBaseline.outcome()).isNull();
	}

	@Test
	void treatsTheTwentyFiveMileBoundaryAsNearAndAPositionBeyondItAsNotNear() {
		BigDecimal boundary = SINGAPORE_LATITUDE.add(new BigDecimal("25").divide(new BigDecimal("60"), 16, RoundingMode.HALF_UP));
		PortConditionComparison onBoundary = compare("MV Ever Steady", "Singapore",
				report(boundary, SINGAPORE_LONGITUDE, "8", 0, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8", 0, hoursAgo(3))));

		assertThat(onBoundary.outcome()).isEqualTo(PortConditionComparison.Outcome.MATCHES_NORMAL);
		assertThat(state(onBoundary).description()).isEqualTo("Under way near Singapore");
	}

	@Test
	void ignoresReportsThatDoNotDefineThisVesselsMovementNearThisPort() {
		AisPosition latest = report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8", 0, minutesAgo(2));
		AisPosition sameInstant = report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "1", 0, latest.ingestedAt());
		AisPosition later = report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "1", 0, minutesAgo(1));
		AisPosition invalid = new AisPosition("563000111", "EVER STEADY", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE,
				new BigDecimal("1"), null, null, 0, false, null, hoursAgo(3), AisPositionSource.AIS_STREAM);
		AisPosition noSpeed = report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, null, 0, hoursAgo(3));
		AisPosition far = report(FAR_LATITUDE, SINGAPORE_LONGITUDE, "1", 0, hoursAgo(3));
		AisPosition usable = report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "5", 0, hoursAgo(4));

		assertThat(compare("MV Ever Steady", "Singapore", latest, List.of(sameInstant, later, invalid, noSpeed, far)).reason())
				.isEqualTo(PortConditionComparison.Reason.NO_BASELINE);
		PortConditionComparison comparison = compare("MV Ever Steady", "Singapore", latest,
				List.of(sameInstant, invalid, noSpeed, far, usable));
		assertThat(comparison.outcome()).isEqualTo(PortConditionComparison.Outcome.MATCHES_NORMAL);
		assertThat(comparison.earlierReportCount()).isEqualTo(1);
		assertThat(comparison.slowestOwnSpeedKnots()).isEqualByComparingTo("5");
	}

	@Test
	void doesNotUseAPartialHistory() {
		PortConditionComparison truncated = PortConditionComparison.of("MV Ever Steady", "Singapore",
				report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8", 0, minutesAgo(2)),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "8", 0, hoursAgo(3))),
				true, NOW, ports);

		assertThat(truncated.reason()).isEqualTo(PortConditionComparison.Reason.BASELINE_TRUNCATED);
		assertThat(truncated.message()).isEqualTo(PortConditionComparison.BASELINE_TRUNCATED_MESSAGE);
		assertThat(truncated.outcome()).isNull();
		assertThat(truncated.earlierReportCount()).isZero();
		assertThat(PortConditionComparisons.EARLIER_REPORT_LIMIT).isEqualTo(500);
	}

	@Test
	void usesAFutureReportAndASeededReport() {
		PortConditionComparison comparison = compare("MV Ever Steady", "Singapore",
				new AisPosition("563000111", "EVER STEADY", SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE,
						new BigDecimal("9"), null, null, 0, true, null, NOW.plus(Duration.ofMinutes(1)),
						AisPositionSource.SEEDED),
				List.of(report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "9", 0, hoursAgo(1))));

		assertThat(comparison.outcome()).isEqualTo(PortConditionComparison.Outcome.MATCHES_NORMAL);
		assertThat(comparison.source()).isEqualTo(AisPositionSource.SEEDED);
	}

	@Test
	void rejectsAComparisonThatContradictsItsSpeeds() {
		AisPosition latest = report(SINGAPORE_LATITUDE, SINGAPORE_LONGITUDE, "3", 0, minutesAgo(2));
		assertThatThrownBy(() -> new PortConditionComparison(PortConditionComparison.Outcome.MATCHES_NORMAL, null,
				PortConditionComparison.MATCHES, PortConditionComparison.ComparedVessel.MOTHER, "MV Ever Steady",
				"Singapore", "Singapore", latest.latitude(), latest.longitude(), latest.speedOverGroundKnots(), 0,
				latest.ingestedAt(), AisPositionSource.AIS_STREAM, 1, new BigDecimal("4")))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> PortConditionComparison.of("MV Ever Steady", "Singapore", latest, List.of(), false, null, ports))
				.isInstanceOf(IllegalArgumentException.class);
	}

	private PortConditionComparison compare(String vessel, String port, AisPosition latest, List<AisPosition> earlier) {
		return PortConditionComparison.of(vessel, port, latest, earlier, false, NOW, ports);
	}

	private OperationalState state(PortConditionComparison comparison) {
		return ports.derive(comparison.navigationalStatus(), comparison.speedOverGroundKnots(), comparison.latitude(),
				comparison.longitude(), comparison.transshipmentPort(), comparison.ingestedAt());
	}

	private AisPosition report(BigDecimal latitude, BigDecimal longitude, String speed, Integer status, Instant ingestedAt) {
		return report(latitude, longitude, speed, status, ingestedAt, AisPositionSource.AIS_STREAM);
	}

	private AisPosition report(BigDecimal latitude, BigDecimal longitude, String speed, Integer status, Instant ingestedAt,
			AisPositionSource source) {
		return new AisPosition("563000111", "EVER STEADY", latitude, longitude,
				speed == null ? null : new BigDecimal(speed), null, null, status, true, null, ingestedAt, source);
	}

	private Instant minutesAgo(long minutes) {
		return NOW.minus(Duration.ofMinutes(minutes));
	}

	private Instant hoursAgo(long hours) {
		return NOW.minus(Duration.ofHours(hours));
	}
}
