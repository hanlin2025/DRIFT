package com.drift.backend.ais.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.ais.feed.AisFeedStatus.Status;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AisFeedStatusIntegrationTests {

	@Autowired AisFeedStatusService feeds;
	@Autowired LatestAisPositions positions;
	@Autowired JdbcTemplate jdbc;

	@BeforeEach
	void clearMemory() {
		positions.clear();
	}

	@Test
	void usesTheNewestStreamReportAndIgnoresANewerSeededReport() {
		Instant stream = Instant.parse("2099-01-01T00:00:00Z");
		Instant seeded = stream.plusSeconds(3600);
		positions.record(position("563176001", "FEED STREAM", stream, AisPositionSource.AIS_STREAM));
		positions.record(position("563176002", "FEED SEEDED", seeded, AisPositionSource.SEEDED));
		OffsetDateTime stored = storedAt("563176001");

		AisFeedStatus status = feeds.current();

		assertThat(status.status()).isEqualTo(Status.LIVE);
		assertThat(status.lastReceivedAt()).isEqualTo(stream);
		assertThat(storedAt("563176001").toInstant()).isEqualTo(stored.toInstant());
		assertThat(storedAt("563176002").toInstant()).isEqualTo(seeded);
	}

	@Test
	void isOfflineWithoutAReportTimeWhenOnlySeededReportsAreStored() {
		jdbc.update("DELETE FROM vessel_observations WHERE source = 'AIS_STREAM'");
		positions.record(position("563176003", "ONLY SEEDED", Instant.parse("2099-06-01T00:00:00Z"),
				AisPositionSource.SEEDED));

		AisFeedStatus status = feeds.current();

		assertThat(status.status()).isEqualTo(Status.OFFLINE);
		assertThat(status.lastReceivedAt()).isNull();
	}

	private OffsetDateTime storedAt(String mmsi) {
		return jdbc.queryForObject("SELECT ingested_at FROM vessel_observations WHERE mmsi = ?", OffsetDateTime.class,
				mmsi);
	}

	private static AisPosition position(String mmsi, String name, Instant ingestedAt, AisPositionSource source) {
		return new AisPosition(mmsi, name, new BigDecimal("1.264"), new BigDecimal("103.840"),
				new BigDecimal("12.0"), null, null, 0, true, null, ingestedAt, source);
	}
}
