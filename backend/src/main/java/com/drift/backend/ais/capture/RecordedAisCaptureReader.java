package com.drift.backend.ais.capture;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.shipment.TrackPoint;
import com.drift.backend.shipment.TrackVessel;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads and validates CDG-285 capture sidecars and their CSV without changing live AIS state. */
@Service
public class RecordedAisCaptureReader {

	private static final Duration MAX_CAPTURE_WINDOW = Duration.ofHours(72);
	private static final long MAX_MANIFEST_BYTES = 1_048_576;
	private static final long MAX_CSV_BYTES = 64L * 1024 * 1024;
	private static final int MAX_MANIFESTS = 5_000;
	private static final List<String> CSV_HEADER = List.of("observation_id", "mmsi", "vessel_name", "latitude",
			"longitude", "speed_over_ground_knots", "course_over_ground_degrees", "true_heading_degrees",
			"navigational_status", "position_valid", "ais_utc_second", "ingested_at_utc", "source");

	private final ObjectMapper objectMapper;
	private final Clock clock;
	private final String captureDirectory;

	@Autowired
	public RecordedAisCaptureReader(ObjectMapper objectMapper, ObjectProvider<Clock> clocks,
			@Value("${app.ais.capture-directory:}") String captureDirectory) {
		this(objectMapper, clocks.getIfAvailable(Clock::systemUTC), captureDirectory);
	}

	RecordedAisCaptureReader(ObjectMapper objectMapper, Clock clock, String captureDirectory) {
		this.objectMapper = objectMapper;
		this.clock = clock;
		this.captureDirectory = captureDirectory == null ? "" : captureDirectory.strip();
	}

	public VerifiedAisCapture read(String rawCaptureId, TrackVessel role, String expectedImo, String expectedMmsi,
			Instant cutoff) {
		UUID captureUuid = parseCaptureId(rawCaptureId);
		if (role == null || expectedImo == null || !validImo(expectedImo) || expectedMmsi == null
				|| !expectedMmsi.matches("[1-9][0-9]{8}") || cutoff == null) {
			throw invalid("Capture identity or cutoff is invalid.");
		}
		if (captureDirectory.isBlank()) {
			throw invalid("Recorded capture playback is not configured.");
		}
		try {
			Path root = Path.of(captureDirectory).toAbsolutePath().normalize();
			if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
				throw invalid("Recorded capture is unavailable.");
			}
			Path manifestPath = findManifest(root, captureUuid);
			JsonNode manifest = readManifest(manifestPath);
			VerifiedAisCapture capture = validateManifestAndCsv(root, manifestPath, manifest, captureUuid,
					role, expectedImo, expectedMmsi, cutoff);
			return capture;
		} catch (InvalidRecordedCaptureException ex) {
			throw ex;
		} catch (IOException | RuntimeException ex) {
			throw new InvalidRecordedCaptureException("Recorded capture is corrupt or unavailable.", ex);
		}
	}

	private Path findManifest(Path root, UUID captureId) throws IOException {
		List<Path> paths;
		try (Stream<Path> walk = Files.walk(root, 4)) {
			paths = walk.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
					.filter(path -> path.getFileName().toString().endsWith(".manifest.json"))
					.limit(MAX_MANIFESTS + 1L)
					.toList();
		}
		if (paths.size() > MAX_MANIFESTS) {
			throw invalid("Recorded capture directory contains too many manifests.");
		}
		Path match = null;
		for (Path path : paths) {
			if (Files.size(path) > MAX_MANIFEST_BYTES) {
				continue;
			}
			try {
				JsonNode node = objectMapper.readTree(Files.readAllBytes(path));
				if (captureId.toString().equalsIgnoreCase(text(node.path("capture_id")))) {
					if (match != null) {
						throw invalid("Capture id is ambiguous.");
					}
					match = path;
				}
			} catch (JacksonException ignored) {
				// An unrelated malformed sidecar cannot make a different capture available.
			}
		}
		if (match == null) {
			throw invalid("Recorded capture was not found.");
		}
		return match;
	}

	private JsonNode readManifest(Path manifestPath) throws IOException {
		if (Files.size(manifestPath) > MAX_MANIFEST_BYTES) {
			throw invalid("Capture manifest is too large.");
		}
		JsonNode manifest = objectMapper.readTree(Files.readAllBytes(manifestPath));
		if (manifest == null || !manifest.isObject()) {
			throw invalid("Capture manifest is malformed.");
		}
		return manifest;
	}

	private VerifiedAisCapture validateManifestAndCsv(Path root, Path manifestPath, JsonNode manifest, UUID captureId,
			TrackVessel role, String expectedImo, String expectedMmsi, Instant cutoff) throws IOException {
		if (manifest.path("format_version").asInt(-1) != 1
				|| !"AUTHENTIC_PROVIDER_CAPTURE".equals(text(manifest.path("classification")))
				|| !captureId.toString().equalsIgnoreCase(text(manifest.path("capture_id")))
				|| !"AISStream via DRIFT's existing consumer; source filter AIS_STREAM".equals(text(manifest.path("source")))) {
			throw invalid("Capture manifest is incompatible.");
		}

		JsonNode vessel = manifest.path("vessel");
		String manifestRole = text(vessel.path("role"));
		String vesselName = text(vessel.path("name"));
		String vesselType = text(vessel.path("type"));
		String mmsi = text(vessel.path("mmsi"));
		String imo = text(vessel.path("imo"));
		if (!role.name().equals(manifestRole) || !expectedImo.equals(imo) || !expectedMmsi.equals(mmsi)
				|| !mmsi.matches("[1-9][0-9]{8}") || !validImo(imo) || vesselName.isBlank()
				|| !vesselType.toLowerCase(Locale.ROOT).contains("container")
				|| text(vessel.path("identity_reference")).isBlank() || !validInstant(text(vessel.path("identity_checked_at_utc")))) {
			throw invalid("Capture vessel identity does not match this shipment.");
		}

		JsonNode window = manifest.path("window");
		Instant from = instant(text(window.path("from_utc_inclusive")));
		Instant to = instant(text(window.path("to_utc_inclusive")));
		if (from == null || to == null || !from.isBefore(to) || Duration.between(from, to).compareTo(MAX_CAPTURE_WINDOW) > 0
				|| cutoff.isBefore(from) || cutoff.isAfter(to)
				|| !"DRIFT receipt time (vessel_observations.ingested_at), not AIS event time".equals(text(window.path("timestamp_semantics")))) {
			throw invalid("Requested cutoff is outside the compatible capture window.");
		}

		JsonNode usage = manifest.path("provider_usage_review");
		LocalDate retainUntil = localDate(text(usage.path("retain_until")));
		if (text(usage.path("reference")).isBlank() || !validInstant(text(usage.path("reviewed_at_utc")))
				|| !"APPROVED".equals(text(usage.path("replay_permission"))) || retainUntil == null
				|| retainUntil.isBefore(LocalDate.now(clock))) {
			throw invalid("Capture replay permission is missing or expired.");
		}
		if (!"ACCEPTED".equals(text(manifest.path("data_quality").path("operator_acceptance")))) {
			throw invalid("Capture has not been accepted by an operator.");
		}

		String csvFile = text(manifest.path("csv_file"));
		if (csvFile.isBlank() || csvFile.contains("/") || csvFile.contains("\\") || csvFile.equals(".") || csvFile.equals("..")) {
			throw invalid("Capture file reference is invalid.");
		}
		Path parent = manifestPath.getParent().toAbsolutePath().normalize();
		Path csvPath = parent.resolve(csvFile).normalize();
		if (!csvPath.getParent().equals(parent) || !csvPath.startsWith(root)
				|| !Files.isRegularFile(csvPath, LinkOption.NOFOLLOW_LINKS) || Files.size(csvPath) > MAX_CSV_BYTES) {
			throw invalid("Capture CSV is unavailable or too large.");
		}
		byte[] csvBytes = Files.readAllBytes(csvPath);
		String expectedSha = text(manifest.path("csv_sha256")).toLowerCase(Locale.ROOT);
		if (!expectedSha.matches("[0-9a-f]{64}") || !expectedSha.equals(sha256(csvBytes))) {
			throw invalid("Capture CSV hash does not match its manifest.");
		}
		ParsedCapture parsed = parseCsv(csvBytes, mmsi, from, to, cutoff);
		if (manifest.path("data_quality").path("observation_count").asInt(-1) != parsed.rowCount()) {
			throw invalid("Capture manifest row count does not match its CSV.");
		}
		return new VerifiedAisCapture(captureId.toString(), manifestRole, vesselName, mmsi, from, to,
				retainUntil, parsed.points());
	}

	private ParsedCapture parseCsv(byte[] csvBytes, String mmsi, Instant from, Instant to, Instant cutoff) {
		String content = utf8(csvBytes);
		try (CSVParser parser = CSVParser.parse(content, CSVFormat.RFC4180.builder()
				.setHeader().setSkipHeaderRecord(true).get())) {
			if (!CSV_HEADER.equals(parser.getHeaderNames())) {
				throw invalid("Capture CSV schema is incompatible.");
			}
			List<TrackPoint> points = new ArrayList<>();
			Set<Long> ids = new HashSet<>();
			int rowCount = 0;
			for (CSVRecord row : parser) {
				rowCount++;
				if (row.size() != CSV_HEADER.size()) {
					throw invalid("Capture CSV contains a malformed row.");
				}
				long id = Long.parseLong(row.get("observation_id"));
				if (id < 1 || !ids.add(id) || !mmsi.equals(row.get("mmsi"))
						|| !AisPositionSource.AIS_STREAM.name().equals(row.get("source"))) {
					throw invalid("Capture CSV contains a duplicate or out-of-scope observation.");
				}
				Instant observedAt = instant(row.get("ingested_at_utc"));
				BigDecimal latitude = new BigDecimal(row.get("latitude"));
				BigDecimal longitude = new BigDecimal(row.get("longitude"));
				BigDecimal speed = decimal(row.get("speed_over_ground_knots"));
				BigDecimal course = decimal(row.get("course_over_ground_degrees"));
				Integer heading = integer(row.get("true_heading_degrees"));
				Integer navStatus = integer(row.get("navigational_status"));
				Integer aisSecond = integer(row.get("ais_utc_second"));
				if (observedAt == null || observedAt.isBefore(from) || observedAt.isAfter(to)
						|| latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0
						|| longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0
						|| !inRange(speed, BigDecimal.ZERO, new BigDecimal("102.3"))
						|| !inRange(course, BigDecimal.ZERO, BigDecimal.valueOf(360))
						|| (heading != null && (heading < 0 || heading > 359))
						|| (navStatus != null && (navStatus < 0 || navStatus > 15))
						|| (aisSecond != null && (aisSecond < 0 || aisSecond > 59))) {
					throw invalid("Capture CSV contains an invalid position or timestamp.");
				}
				String validRaw = row.get("position_valid");
				Boolean positionValid = validRaw.isBlank() ? null : parseBoolean(validRaw);
				if (Boolean.FALSE.equals(positionValid)) {
					continue;
				}
				if (observedAt.isAfter(cutoff)) {
					continue;
				}
				points.add(new TrackPoint(id, latitude, longitude, speed, course, heading,
						observedAt, AisPositionSource.AIS_STREAM, positionValid));
			}
			points.sort(Comparator.comparing(TrackPoint::ingestedAt).thenComparingLong(TrackPoint::id));
			return new ParsedCapture(List.copyOf(points), rowCount);
		} catch (IOException | NumberFormatException | IllegalStateException ex) {
			throw new InvalidRecordedCaptureException("Capture CSV is malformed.", ex);
		}
	}

	private static String utf8(byte[] bytes) {
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
					.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException ex) {
			throw new InvalidRecordedCaptureException("Capture CSV is not valid UTF-8.", ex);
		}
	}

	private static UUID parseCaptureId(String raw) {
		UUID uuid;
		try {
			uuid = UUID.fromString(raw == null ? "" : raw);
		} catch (IllegalArgumentException ex) {
			throw invalid("Capture id must be a UUID.");
		}
		if (!uuid.toString().equalsIgnoreCase(raw)) throw invalid("Capture id must be a UUID.");
		return uuid;
	}

	private static String text(JsonNode node) {
		return node != null && node.isTextual() ? node.asText().strip() : "";
	}

	private static boolean validInstant(String value) {
		return instant(value) != null;
	}

	private static Instant instant(String value) {
		try {
			return OffsetDateTime.parse(value).toInstant();
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static LocalDate localDate(String value) {
		try {
			return LocalDate.parse(value);
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static BigDecimal decimal(String value) {
		return value == null || value.isBlank() ? null : new BigDecimal(value);
	}

	private static Integer integer(String value) {
		return value == null || value.isBlank() ? null : Integer.valueOf(value);
	}

	private static boolean inRange(BigDecimal value, BigDecimal minimumInclusive, BigDecimal maximumExclusive) {
		return value == null || (value.compareTo(minimumInclusive) >= 0 && value.compareTo(maximumExclusive) < 0);
	}

	private static Boolean parseBoolean(String value) {
		if ("true".equalsIgnoreCase(value) || "t".equalsIgnoreCase(value)) return Boolean.TRUE;
		if ("false".equalsIgnoreCase(value) || "f".equalsIgnoreCase(value)) return Boolean.FALSE;
		throw invalid("Capture CSV contains an invalid position-validity flag.");
	}

	private static boolean validImo(String imo) {
		if (imo == null || !imo.matches("[0-9]{7}")) return false;
		int sum = 0;
		for (int i = 0; i < 6; i++) sum += Character.digit(imo.charAt(i), 10) * (7 - i);
		return sum % 10 == Character.digit(imo.charAt(6), 10);
	}

	private static String sha256(byte[] value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
			StringBuilder hex = new StringBuilder(digest.length * 2);
			for (byte b : digest) hex.append(String.format("%02x", b));
			return hex.toString();
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is not available", ex);
		}
	}

	private static InvalidRecordedCaptureException invalid(String message) {
		return new InvalidRecordedCaptureException(message);
	}

	private record ParsedCapture(List<TrackPoint> points, int rowCount) {
	}
}
