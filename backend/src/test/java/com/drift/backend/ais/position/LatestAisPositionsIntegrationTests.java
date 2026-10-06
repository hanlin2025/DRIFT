package com.drift.backend.ais.position;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
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
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void clear() {
		positions.clear();
	}

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
	void keepsTheNewerReportForTheSameVessel() {
		positions.record(position("368207620", "Ever Steady", Instant.parse("2026-10-05T01:00:00Z")));
		positions.record(position("368207620", "EVER   STEADY", Instant.parse("2026-10-05T02:00:00Z")));
		positions.record(position("368207620", "Ever Steady", Instant.parse("2026-10-05T00:30:00Z")));

		AisPosition latest = positions.findByVesselName("ever steady").orElseThrow();
		assertThat(latest.ingestedAt()).isEqualTo(Instant.parse("2026-10-05T02:00:00Z"));
		assertThat(positions.findByMmsi("368207620").orElseThrow()).isEqualTo(latest);

		positions.clear();
		assertThat(positions.findByMmsi("368207620")).isEmpty();
		assertThat(positions.findByVesselName("ever steady").orElseThrow().ingestedAt())
				.isEqualTo(Instant.parse("2026-10-05T02:00:00Z"));
	}

	@Test
	void keepsTheNewestPositionWhenTwoMmsisShareAVesselName() {
		positions.record(position("563001234", "PACIFIC HORIZON", Instant.parse("2026-10-05T03:00:00Z")));
		positions.record(position("563009999", "Pacific   Horizon", Instant.parse("2026-10-05T01:00:00Z")));

		AisPosition latest = positions.findByVesselName("pacific horizon").orElseThrow();
		assertThat(latest.mmsi()).isEqualTo("563001234");
		assertThat(latest.ingestedAt()).isEqualTo(Instant.parse("2026-10-05T03:00:00Z"));
	}

	@Test
	void keepsTheRequestedNameWhenAVesselIsRenamed() {
		positions.record(position("563001234", "PACIFIC HORIZON", Instant.parse("2026-10-05T01:00:00Z")));
		positions.record(position("563009999", "Pacific Horizon", Instant.parse("2026-10-05T02:00:00Z")));
		positions.record(position("563001234", "PACIFIC STAR", Instant.parse("2026-10-05T03:00:00Z")));

		AisPosition horizon = positions.findByVesselName("pacific horizon").orElseThrow();
		assertThat(horizon.mmsi()).isEqualTo("563009999");
		assertThat(horizon.vesselName()).isEqualTo("Pacific Horizon");
		assertThat(horizon.ingestedAt()).isEqualTo(Instant.parse("2026-10-05T02:00:00Z"));

		AisPosition star = positions.findByVesselName("pacific star").orElseThrow();
		assertThat(star.mmsi()).isEqualTo("563001234");
		assertThat(star.ingestedAt()).isEqualTo(Instant.parse("2026-10-05T03:00:00Z"));
	}

	@Test
	void findsAStoredNameWithBoundaryTabsAfterMemoryIsCleared() {
		positions.record(position("563001234", "\tPacific\tHorizon\t", Instant.parse("2026-10-05T03:00:00Z")));
		positions.clear();

		AisPosition latest = positions.findByVesselName("pacific horizon").orElseThrow();
		assertThat(latest.mmsi()).isEqualTo("563001234");
		assertThat(latest.vesselName()).isEqualTo("\tPacific\tHorizon\t");
		assertThat(jdbc.queryForObject("""
				SELECT indexdef FROM pg_indexes WHERE indexname = 'vessel_observations_vessel_name_latest'
				""", String.class)).contains("ingested_at DESC", "id DESC");
	}

	private int countObservations(String mmsi) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM vessel_observations WHERE mmsi = ?", Integer.class, mmsi);
	}

	private static AisPosition position(String mmsi, String name, Instant ingestedAt) {
		return new AisPosition(mmsi, name, new BigDecimal("1.3"), new BigDecimal("103.8"),
				null, null, null, null, null, null, ingestedAt, AisPositionSource.AIS_STREAM);
	}
}
