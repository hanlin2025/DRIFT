package com.drift.backend.ais.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.shipment.TrackVessel;

import tools.jackson.databind.json.JsonMapper;

class RecordedAisCaptureReaderTest {

	private static final String ID = "f2c37d87-b31e-4cbc-8f50-c8d548c9b9f9";
	private static final String MMSI = "563123456";
	private static final Instant CUTOFF = Instant.parse("2026-10-30T12:00:30Z");
	private static final String CSV = """
			observation_id,mmsi,vessel_name,latitude,longitude,speed_over_ground_knots,course_over_ground_degrees,true_heading_degrees,navigational_status,position_valid,ais_utc_second,ingested_at_utc,source
			42,563123456,MV Test Vessel,1.264,103.82,8.5,175.0,176,0,true,30,2026-10-30T12:00:30Z,AIS_STREAM
			41,563123456,MV Test Vessel,1.263,103.81,,,,,true,,2026-10-30T12:00:30Z,AIS_STREAM
			""";

	@TempDir Path temporary;

	private final JsonMapper mapper = JsonMapper.builder().build();

	@Test
	void verifiesAnAcceptedManifestAndPreservesDistinctTiedReports() throws Exception {
		RecordedAisCaptureReader reader = reader("APPROVED", "ACCEPTED", "2026-11-01", false, "MOTHER");

		VerifiedAisCapture capture = reader.read(ID, TrackVessel.MOTHER, "1234567", MMSI, CUTOFF);

		assertThat(capture.captureId()).isEqualTo(ID);
		assertThat(capture.points()).hasSize(2);
		assertThat(capture.points()).extracting(point -> point.id()).containsExactly(41L, 42L);
		assertThat(capture.points()).allSatisfy(point -> {
			assertThat(point.source()).isEqualTo(AisPositionSource.AIS_STREAM);
			assertThat(point.ingestedAt()).isEqualTo(CUTOFF);
		});
		assertThat(capture.points().getFirst().speedOverGroundKnots()).isNull();
	}

	@Test
	void rejectsUnapprovedExpiredCorruptAndIncompatibleCaptures() throws Exception {
		assertRejected("UNKNOWN", "ACCEPTED", "2026-11-01", false, "MOTHER", CUTOFF);
		assertRejected("APPROVED", "PENDING", "2026-11-01", false, "MOTHER", CUTOFF);
		assertRejected("APPROVED", "ACCEPTED", "2026-10-29", false, "MOTHER", CUTOFF);
		assertRejected("APPROVED", "ACCEPTED", "2026-11-01", true, "MOTHER", CUTOFF);
		assertRejected("APPROVED", "ACCEPTED", "2026-11-01", false, "FEEDER", CUTOFF);
		assertRejected("APPROVED", "ACCEPTED", "2026-11-01", false, "MOTHER",
				Instant.parse("2026-10-30T13:01:00Z"));
		assertThatThrownBy(() -> reader("APPROVED", "ACCEPTED", "2026-11-01", false, "MOTHER")
				.read(ID, TrackVessel.MOTHER, "2234564", MMSI, CUTOFF))
				.isInstanceOf(InvalidRecordedCaptureException.class);
		assertThatThrownBy(() -> reader("APPROVED", "ACCEPTED", "2026-11-01", false, "MOTHER")
				.read(UUID.randomUUID().toString(), TrackVessel.MOTHER, "1234567", MMSI, CUTOFF))
				.isInstanceOf(InvalidRecordedCaptureException.class);
	}

	private void assertRejected(String permission, String acceptance, String retention, boolean corrupt,
			String manifestRole, Instant cutoff) throws Exception {
		RecordedAisCaptureReader reader = reader(permission, acceptance, retention, corrupt, manifestRole);
		assertThatThrownBy(() -> reader.read(ID, TrackVessel.MOTHER, "1234567", MMSI, cutoff))
				.isInstanceOf(InvalidRecordedCaptureException.class);
	}

	private RecordedAisCaptureReader reader(String permission, String acceptance, String retention,
			boolean corruptCsv, String manifestRole) throws Exception {
		Path dir = Files.createDirectory(temporary.resolve(UUID.randomUUID().toString()));
		String csv = CSV;
		byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
		Files.write(dir.resolve("capture.csv"), bytes);
		String hash = corruptCsv ? "0".repeat(64)
				: HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		Map<String, Object> manifest = manifest(permission, acceptance, retention, manifestRole, hash);
		Files.write(dir.resolve("capture.manifest.json"), mapper.writeValueAsBytes(manifest));
		return new RecordedAisCaptureReader(mapper, Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneOffset.UTC),
				dir.toString());
	}

	private Map<String, Object> manifest(String permission, String acceptance, String retention,
			String role, String hash) {
		Map<String, Object> vessel = new LinkedHashMap<>();
		vessel.put("role", role);
		vessel.put("name", "MV Test Vessel");
		vessel.put("type", "container");
		vessel.put("imo", "1234567");
		vessel.put("mmsi", MMSI);
		vessel.put("identity_reference", "test registry reference");
		vessel.put("identity_checked_at_utc", "2026-10-30T11:00:00Z");
		Map<String, Object> window = Map.of(
				"from_utc_inclusive", "2026-10-30T11:00:00Z",
				"to_utc_inclusive", "2026-10-30T13:00:00Z",
				"timestamp_semantics", "DRIFT receipt time (vessel_observations.ingested_at), not AIS event time");
		Map<String, Object> usage = Map.of("reference", "test provider review", "reviewed_at_utc", "2026-10-30T11:00:00Z",
				"replay_permission", permission, "retain_until", retention);
		Map<String, Object> quality = Map.of("operator_acceptance", acceptance, "observation_count", 2);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("format_version", 1);
		result.put("capture_id", ID);
		result.put("classification", "AUTHENTIC_PROVIDER_CAPTURE");
		result.put("source", "AISStream via DRIFT's existing consumer; source filter AIS_STREAM");
		result.put("vessel", vessel);
		result.put("window", window);
		result.put("provider_usage_review", usage);
		result.put("csv_file", "capture.csv");
		result.put("csv_sha256", hash);
		result.put("data_quality", quality);
		return result;
	}
}
