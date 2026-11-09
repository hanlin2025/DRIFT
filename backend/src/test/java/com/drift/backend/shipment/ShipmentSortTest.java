package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.exception.InvalidShipmentRequestException;

class ShipmentSortTest {

	private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-10-20T00:00:00Z");

	@Test
	void blankKeepsNewestFirstAndRiskIsReadCaseInsensitively() {
		assertThat(ShipmentSort.fromListParameter(null)).isEqualTo(ShipmentSort.CREATED_DATE_DESCENDING);
		assertThat(ShipmentSort.fromListParameter("  ")).isEqualTo(ShipmentSort.CREATED_DATE_DESCENDING);
		assertThat(ShipmentSort.fromListParameter(" Risk ")).isEqualTo(ShipmentSort.RISK_FIRST);
	}

	@Test
	void rejectsUnknownSortValuesWithAStructuredError() {
		assertThatThrownBy(() -> ShipmentSort.fromListParameter("newest"))
				.isInstanceOfSatisfying(InvalidShipmentRequestException.class,
						e -> assertThat(e.getErrors()).containsEntry("sort", "Sort must be risk"));
		assertThatThrownBy(() -> ShipmentSort.fromListParameter("priority"))
				.isInstanceOfSatisfying(InvalidShipmentRequestException.class,
						e -> assertThat(e.getErrors()).containsEntry("sort", "Sort must be risk"));
	}

	@Test
	void calculatedSortsAreNeverSentToTheDatabaseAndOnlyPutTheMostUrgentFirst() {
		assertThat(ShipmentSort.RISK_FIRST.computed()).isTrue();
		assertThat(ShipmentSort.CREATED_DATE_DESCENDING.computed()).isFalse();
		assertThatThrownBy(ShipmentSort.RISK_FIRST::toSort).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new ShipmentSort(ShipmentSort.Field.RISK, ShipmentSort.Direction.ASC))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void riskOrderIsCriticalToLowThenNotAssessedWithTheSoonestFeederDepartureBreakingTies() {
		List<ShipmentResponse> rows = List.of(
				row(1, null, DEPARTURE),
				row(2, ShipmentRisk.Level.LOW, DEPARTURE),
				row(3, ShipmentRisk.Level.HIGH, DEPARTURE.plusHours(5)),
				row(4, ShipmentRisk.Level.CRITICAL, DEPARTURE.plusDays(3)),
				row(5, ShipmentRisk.Level.HIGH, DEPARTURE.plusHours(1)),
				row(6, ShipmentRisk.Level.MEDIUM, DEPARTURE));

		assertThat(rows.stream().sorted(ShipmentSort.RISK_FIRST.comparator()).map(ShipmentResponse::id))
				.containsExactly(4L, 5L, 3L, 6L, 2L, 1L);
	}

	@Test
	void withinARiskLevelAPredictedArrivalAfterTheImportersDeadlineComesFirst() {
		List<ShipmentResponse> rows = List.of(
				row(1, ShipmentRisk.Level.HIGH, DEPARTURE, null),
				row(2, ShipmentRisk.Level.HIGH, DEPARTURE.plusHours(4), DelayComparison.Outcome.EXCEEDS_DEADLINE),
				row(3, ShipmentRisk.Level.HIGH, DEPARTURE.plusHours(1), DelayComparison.Outcome.NO_PREDICTED_IMPACT),
				row(4, ShipmentRisk.Level.MEDIUM, DEPARTURE, DelayComparison.Outcome.EXCEEDS_DEADLINE),
				row(5, ShipmentRisk.Level.CRITICAL, DEPARTURE.plusDays(1), null));

		assertThat(rows.stream().sorted(ShipmentSort.RISK_FIRST.comparator()).map(ShipmentResponse::id))
				.containsExactly(5L, 2L, 1L, 3L, 4L);
	}

	private static ShipmentResponse row(long id, ShipmentRisk.Level level, OffsetDateTime feederDeparture) {
		return row(id, level, feederDeparture, null);
	}

	private static ShipmentResponse row(long id, ShipmentRisk.Level level, OffsetDateTime feederDeparture,
			DelayComparison.Outcome outcome) {
		ShipmentRisk risk = level == null ? null : new ShipmentRisk(level, level.name());
		DelayComparison comparison = outcome == null
				? new DelayComparison(null, DelayComparison.Reason.MISSING_CONSTRAINT,
						DelayComparison.MISSING_CONSTRAINT_MESSAGE, null, null, null)
				: new DelayComparison(outcome, null, outcome.name(), feederDeparture.minusHours(8),
						feederDeparture.minusHours(9), "Singapore");
		return new ShipmentResponse(id, 1L, "HBL-" + id, "Jakarta", "Rotterdam", "Singapore", "MV Mother",
				feederDeparture.minusHours(8), "MV Feeder", feederDeparture, null, 0L, null, risk, comparison, null,
				ShipmentStatus.ACTIVE, null, null);
	}
}
