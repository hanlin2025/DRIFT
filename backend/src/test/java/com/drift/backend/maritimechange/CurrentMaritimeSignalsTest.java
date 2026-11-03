package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class CurrentMaritimeSignalsTest {

	private static final Instant NOW = Instant.parse("2026-11-03T02:00:00Z");
	private static final String MOTHER = "9351048";
	private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-11-05T08:00:00Z");
	private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-11-05T20:00:00Z");

	private final Rows rows = new Rows();
	private final CurrentMaritimeSignals signals = new CurrentMaritimeSignals(rows,
			new MaritimeSnapshotShipmentMatcher(companyId -> List.of()), Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void currentWeatherIsMatchedAtTheTimeOfTheReadNotThePlannedArrival() {
		rows.add(row("NEA_WEATHER", "nea:forecast:Tuas", null, "Tuas", "2026-11-03T01:00:00Z", "2026-11-03T03:00:00Z",
				null, "Thundery Showers"));

		Map<Long, List<MaritimeSignal>> found = signals.forShipments(List.of(shipment(1L, "Singapore")));

		assertThat(found.get(1L)).singleElement().satisfies(signal -> {
			assertThat(signal.association()).isEqualTo("REGIONAL_CONTEXT");
			assertThat(signal.timeCompatibility()).isEqualTo("COMPATIBLE");
			assertThat(signal.currentValue()).isEqualTo("Thundery Showers");
		});
	}

	@Test
	void weatherIsNotMatchedOutsideSingapore() {
		rows.add(row("NEA_WEATHER", "nea:forecast:Tuas", null, "Tuas", "2026-11-03T01:00:00Z", "2026-11-03T03:00:00Z",
				null, "Thundery Showers"));

		assertThat(signals.forShipments(List.of(shipment(1L, "Port Klang")))).isEmpty();
	}

	@Test
	void anExpectedArrivalForTheMotherVesselIsVerifiedAtTheSamePortCall() {
		rows.add(row("MPA_ETA_REVISION", "mpa:due-to-arrive:" + MOTHER, MOTHER, "Singapore", "2026-11-02T08:00:00Z",
				"2026-11-08T08:00:00Z", "2026-11-05T08:00:00Z", "2026-11-05T11:00:00Z"));

		assertThat(signals.forShipments(List.of(shipment(1L, "Singapore"))).get(1L)).singleElement().satisfies(signal -> {
			assertThat(signal.association()).isEqualTo("VERIFIED_ASSOCIATION");
			assertThat(signal.vesselRole()).isEqualTo("MOTHER");
			assertThat(signal.previousValue()).isEqualTo("2026-11-05T08:00:00Z");
		});
	}

	@Test
	void anOldNoticeAndAPastArrivalAreNotUsed() {
		rows.add(row("MPA_OFFICIAL_NOTICE", "mpa:notice:1", null, "Singapore", "2026-10-01T00:00:00Z", null, null,
				"Port closure"));
		rows.add(row("MPA_ETA_REVISION", "mpa:due-to-arrive:" + MOTHER, MOTHER, "Singapore", "2026-10-29T00:00:00Z",
				"2026-11-04T00:00:00Z", null, "2026-11-01T00:00:00Z"));

		assertThat(signals.forShipments(List.of(shipment(1L, "Singapore")))).isEmpty();
	}

	@Test
	void aRecentNoticeAtThePortIsUnconfirmedContext() {
		rows.add(row("MPA_OFFICIAL_NOTICE", "mpa:notice:2", null, "Tuas, Singapore", "2026-11-01T00:00:00Z", null, null,
				"Temporary closure of Tuas fairway"));

		assertThat(signals.forShipments(List.of(shipment(1L, "Singapore"))).get(1L)).singleElement()
				.extracting(MaritimeSignal::association).isEqualTo("UNCONFIRMED_CONTEXT");
	}

	@Test
	void noShipmentReadsNothing() {
		assertThat(signals.forShipments(List.of())).isEmpty();
		assertThat(rows.reads).isZero();
	}

	private static ShipmentMatchFacts shipment(long id, String port) {
		return new ShipmentMatchFacts(id, 4L, port, MOTHER, "7654329", ARRIVAL, DEPARTURE);
	}

	private static MaritimeSnapshotObservation row(String type, String identity, String imo, String location, String from,
			String until, String previous, String current) {
		MaritimeSnapshotObservation row = MaritimeSnapshotObservation.record(type, identity, "f".repeat(64), "NEW", "FORWARD",
				true, "test", null, null, NOW, "LIVE", "APPLICABLE", imo, location,
				from == null ? null : Instant.parse(from), until == null ? null : Instant.parse(until), previous, current,
				null, null, null, NOW);
		row.markHead();
		return row;
	}

	private static final class Rows implements MaritimeSnapshotObservationStore {
		private final List<MaritimeSnapshotObservation> rows = new ArrayList<>();
		private int reads;

		void add(MaritimeSnapshotObservation row) {
			rows.add(row);
		}

		@Override
		public Optional<MaritimeSnapshotObservation> head(String sourceType, String sourceIdentity) {
			return Optional.empty();
		}

		@Override
		public Optional<MaritimeSnapshotObservation> find(String sourceType, String sourceIdentity, String contentFingerprint) {
			return Optional.empty();
		}

		@Override
		public void add(MaritimeSnapshotObservation observation, boolean becomesHead) {
		}

		@Override
		public List<MaritimeSnapshotObservation> currentHeads(Instant now) {
			reads++;
			return rows.stream()
					.filter(row -> row.getEffectiveUntil() == null || row.getEffectiveUntil().isAfter(now))
					.toList();
		}
	}
}
