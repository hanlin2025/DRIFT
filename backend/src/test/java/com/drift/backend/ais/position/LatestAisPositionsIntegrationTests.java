package com.drift.backend.ais.position;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LatestAisPositionsIntegrationTests {

	@Autowired LatestAisPositions positions;
	@Autowired VesselObservationRepository observations;
	@Autowired JdbcTemplate jdbc;

	@Test
	void storesEachObservationWithItsSourceAndMetadata() {
		positions.record(new AisPosition("368207620", "EVER STEADY", new BigDecimal("1.2640166"),
				new BigDecimal("103.8201833"), new BigDecimal("12.4"), new BigDecimal("86.7"), 87, 5, true, 42,
				Instant.parse("2026-10-05T01:00:00Z"), AisPositionSource.AIS_STREAM));

		Map<String, Object> row = jdbc.queryForMap("SELECT * FROM vessel_observations WHERE mmsi = '368207620'");
		assertThat(row)
				.containsEntry("vessel_name", "EVER STEADY")
				.containsEntry("true_heading_degrees", 87)
				.containsEntry("navigational_status", 5)
				.containsEntry("position_valid", true)
				.containsEntry("ais_utc_second", 42)
				.containsEntry("source", "AIS_STREAM");
		assertThat((BigDecimal) row.get("latitude")).isEqualByComparingTo("1.2640166");
		assertThat((BigDecimal) row.get("longitude")).isEqualByComparingTo("103.8201833");
		assertThat((BigDecimal) row.get("speed_over_ground_knots")).isEqualByComparingTo("12.4");
		assertThat((BigDecimal) row.get("course_over_ground_degrees")).isEqualByComparingTo("86.7");
		assertThat(jdbc.queryForObject("SELECT ingested_at FROM vessel_observations WHERE mmsi = '368207620'",
				OffsetDateTime.class)).isEqualTo(OffsetDateTime.of(2026, 10, 5, 1, 0, 0, 0, ZoneOffset.UTC));
	}

	@Test
	void keepsEveryObservationAndUnavailableValuesAsNull() {
		positions.record(position("368207620", "Ever Steady", Instant.parse("2026-10-05T01:00:00Z")));
		positions.record(position("368207620", "Ever Steady", Instant.parse("2026-10-05T02:00:00Z")));

		assertThat(countObservations("368207620")).isEqualTo(2);
		assertThat(jdbc.queryForMap("""
				SELECT speed_over_ground_knots, course_over_ground_degrees, true_heading_degrees, ais_utc_second
				FROM vessel_observations WHERE mmsi = '368207620' LIMIT 1
				""").values()).containsOnlyNulls();
	}

	@Test
	void returnsTheNewestStoredObservationForAVessel() {
		positions.record(position("368207620", "Ever Steady", Instant.parse("2026-10-05T01:00:00Z")));
		positions.record(position("368207620", "EVER   STEADY", Instant.parse("2026-10-05T02:00:00Z")));
		positions.record(position("368207620", "Ever Steady", Instant.parse("2026-10-05T00:30:00Z")));

		AisPosition latest = positions.findByMmsi(" 368207620 ").orElseThrow();
		assertThat(latest.ingestedAt()).isEqualTo(Instant.parse("2026-10-05T02:00:00Z"));
		assertThat(latest.vesselName()).isEqualTo("EVER   STEADY");
		assertThat(latest.source()).isEqualTo(AisPositionSource.AIS_STREAM);
		assertThat(positions.findByVesselName(" ever steady ").orElseThrow()).isEqualTo(latest);
	}

	@Test
	void readsStoredObservationsWithoutTheInstanceThatRecordedThem() {
		positions.record(new AisPosition("368207620", "EVER STEADY", new BigDecimal("1.3"), new BigDecimal("103.8"),
				new BigDecimal("12.4"), new BigDecimal("86.7"), 87, 5, true, 42,
				Instant.parse("2026-10-05T01:00:00Z"), AisPositionSource.AIS_STREAM));

		AisPosition stored = new LatestAisPositions(observations).findByMmsi("368207620").orElseThrow();
		assertThat(stored.speedOverGroundKnots()).isEqualByComparingTo("12.4");
		assertThat(stored.courseOverGroundDegrees()).isEqualByComparingTo("86.7");
		assertThat(stored.trueHeadingDegrees()).isEqualTo(87);
		assertThat(stored.navigationalStatus()).isEqualTo(5);
		assertThat(stored.positionValid()).isTrue();
		assertThat(stored.aisUtcSecond()).isEqualTo(42);
	}

	@Test
	void reportsWhetherTheNewestObservationIsLiveOrSeeded() {
		positions.record(position("563001234", "PACIFIC HORIZON", Instant.parse("2026-10-05T01:00:00Z")));
		jdbc.update("""
				INSERT INTO vessel_observations (mmsi, vessel_name, latitude, longitude, ingested_at, source)
				VALUES ('563001234', 'PACIFIC HORIZON', 1.25, 103.7, '2026-10-05T02:00:00Z', 'SEEDED')
				""");

		AisPosition latest = positions.findByVesselName("Pacific Horizon").orElseThrow();
		assertThat(latest.source()).isEqualTo(AisPositionSource.SEEDED);
		assertThat(latest.latitude()).isEqualByComparingTo("1.25");
	}

	@Test
	void findsTheNewestObservationForANamedVesselEvenWhenItIsNameless() {
		positions.record(position("563001234", "PACIFIC HORIZON", new BigDecimal("1.20"),
				Instant.parse("2026-10-05T01:00:00Z")));
		positions.record(position("563001234", "PACIFIC HORIZON", new BigDecimal("1.20"),
				Instant.parse("2026-10-05T02:00:00Z")));
		positions.record(position("563001234", null, new BigDecimal("1.30"), Instant.parse("2026-10-05T03:00:00Z")));

		AisPosition latest = positions.findByVesselName(" pacific   horizon ").orElseThrow();
		assertThat(latest.ingestedAt()).isEqualTo(Instant.parse("2026-10-05T03:00:00Z"));
		assertThat(latest.latitude()).isEqualByComparingTo("1.30");
		assertThat(latest).isEqualTo(positions.findByMmsi("563001234").orElseThrow());
	}

	@Test
	void findsNothingForAnUnknownOrBlankVessel() {
		positions.record(position("368207620", "Ever Steady", Instant.parse("2026-10-05T01:00:00Z")));

		assertThat(positions.findByMmsi("563009999")).isEmpty();
		assertThat(positions.findByVesselName("Strait Runner")).isEmpty();
		assertThat(positions.findByMmsi(" ")).isEmpty();
		assertThat(positions.findByVesselName(null)).isEmpty();
	}

	private int countObservations(String mmsi) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM vessel_observations WHERE mmsi = ?", Integer.class, mmsi);
	}

	private static AisPosition position(String mmsi, String name, Instant ingestedAt) {
		return position(mmsi, name, new BigDecimal("1.3"), ingestedAt);
	}

	private static AisPosition position(String mmsi, String name, BigDecimal latitude, Instant ingestedAt) {
		return new AisPosition(mmsi, name, latitude, new BigDecimal("103.8"),
				null, null, null, null, null, null, ingestedAt, AisPositionSource.AIS_STREAM);
	}
}
