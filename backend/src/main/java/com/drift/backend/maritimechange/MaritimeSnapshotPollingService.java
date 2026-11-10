package com.drift.backend.maritimechange;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Runs the repository's established collectors in isolated temp directories, then maps their outputs to snapshots. */
@Service
class MaritimeSnapshotPollingService {
	private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
	private static final DateTimeFormatter MPA_LOCAL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final DateTimeFormatter MPA_OFFSET_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX");

	private final ObjectMapper mapper;
	private final MaritimeSnapshotNormalizer normalizer;
	private final Path projectDirectory;
	private final String python;
	private final long timeoutSeconds;
	private final int retries;
	private final int lookbackDays;

	MaritimeSnapshotPollingService(ObjectMapper mapper, MaritimeSnapshotNormalizer normalizer,
			@Value("${app.maritime-change.collection.project-directory:.}") String projectDirectory,
			@Value("${app.maritime-change.collection.python:python3}") String python,
			@Value("${app.maritime-change.collection.source-timeout-seconds:300}") long timeoutSeconds,
			@Value("${app.maritime-change.collection.retries:1}") int retries,
			@Value("${app.maritime-change.collection.mpa-lookback-days:3}") int lookbackDays) {
		this.mapper = mapper;
		this.normalizer = normalizer;
		this.projectDirectory = Path.of(projectDirectory).toAbsolutePath().normalize();
		this.python = python;
		this.timeoutSeconds = Math.max(30, Math.min(300, timeoutSeconds));
		this.retries = Math.max(0, Math.min(2, retries));
		this.lookbackDays = Math.max(0, Math.min(14, lookbackDays));
	}

	List<MaritimeCollectionPoll> poll() {
		return List.of(runMpaExpected(), runNotices(), runNea());
	}

	private MaritimeCollectionPoll runMpaExpected() {
		return run("MPA_EXPECTED_ARRIVALS", "tools/cdg201/mpa/mpa_oceansx_collect.py", temp -> {
			List<String> args = List.of("--rolling", "--feed-group", "expected", "--lookback-days",
					Integer.toString(lookbackDays), "--lookahead-days", "3", "--output", temp.toString());
			invoke("tools/cdg201/mpa/mpa_oceansx_collect.py", args);
			Instant retrieved = Instant.now();
			List<MaritimeSnapshot> out = new ArrayList<>();
			for (Path manifest : jsonFiles(temp, "due_to_arrive_*.manifest.json")) {
				JsonNode m = read(manifest);
				if (!m.path("schema_valid").asBoolean(false) || !m.path("error").asText("").isBlank()) {
					throw new IOException("MPA expected-arrival collector returned an invalid source page");
				}
				String rawName = m.path("raw_file").asText("");
				if (rawName.isBlank()) continue;
				JsonNode records = read(temp.resolve(rawName));
				if (!records.isArray()) continue;
				String retrievedAt = m.path("retrieval_completed_utc").asText(m.path("retrieval_started_utc").asText(retrieved.toString()));
				String provenance = "tools/cdg201/mpa/mpa_oceansx_collect.py:" + manifest.getFileName();
				for (JsonNode record : records) {
					JsonNode vessel = record.path("vesselParticulars");
					String imo = firstText(vessel, "imoNumber", "imo");
					String arrival = firstText(record, "duetoArriveTime", "dueToArriveTime", "due_to_arrive_time");
					if (imo.isBlank() || arrival.isBlank()) continue;
					String location = firstText(record, "locationTo", "location_to", "port");
					String departure = firstText(record, "duetoDepartTime", "dueToDepartTime", "due_to_depart_time");
					Instant eventInstant = instant(arrival);
					if (eventInstant == null) continue;
					String normalizedArrival = formatMpaTimestamp(eventInstant);
					Instant departureInstant = instant(departure);
					String normalizedDeparture = departureInstant == null ? null : formatMpaTimestamp(departureInstant);
					boolean historical = eventInstant.isBefore(Instant.now());
					String freshness = !historical
							? MaritimeSnapshotChangeDetector.APPLICABLE : MaritimeSnapshotChangeDetector.HISTORICAL;
					String value = "arrival=" + arrival + ";departure=" + departure + ";port=" + location;
					MaritimeSourceObservation source = new MaritimeSourceObservation(
							MaritimeSnapshotChangeDetector.MPA_ETA_REVISION, "mpa:" + imo + ":" + arrivalIdentity(record),
							provenance, normalizedArrival, retrievedAt, retrievedAt, MaritimeSnapshotChangeDetector.LIVE, freshness,
							imo, location.isBlank() ? "Singapore" : location, normalizedArrival, normalizedDeparture,
							value, MaritimeSnapshotChangeDetector.UNKNOWN, MaritimeSnapshotChangeDetector.UNKNOWN,
							MaritimeSnapshotChangeDetector.UNKNOWN);
					out.add(normalizer.normalize(source));
				}
			}
			if (jsonFiles(temp, "due_to_arrive_*.manifest.json").isEmpty()) {
				throw new IOException("MPA expected-arrival collector returned no source manifests");
			}
			return out;
		});
	}

	private MaritimeCollectionPoll runNotices() {
		return run("MPA_OFFICIAL_NOTICES", "tools/maritime_notices/mpa_official_notices_collect.py", temp -> {
			invoke("tools/maritime_notices/mpa_official_notices_collect.py", List.of("--raw-output", temp.toString(),
					"--year", Integer.toString(java.time.Year.now(ZoneOffset.UTC).getValue()), "--limit", "100",
					"--observation-mode", "prospective"));
			List<MaritimeSnapshot> out = new ArrayList<>();
			for (Path manifest : jsonFiles(temp, "*.manifest.json")) {
				JsonNode m = read(manifest);
				if (!m.path("schema_valid").asBoolean(false) || !m.path("valid_coverage").asBoolean(false)) {
					throw new IOException("MPA notice collector returned an incomplete listing");
				}
				String retrieved = m.path("retrieval_completed_utc").asText(m.path("retrieval_started_utc").asText(Instant.now().toString()));
				String provenance = "tools/maritime_notices/mpa_official_notices_collect.py:" + manifest.getFileName();
				JsonNode pages = m.path("pages");
				for (JsonNode page : pages) {
					String rawName = page.path("raw_file").asText("");
					if (page.path("http_status").asInt() != 200 || !page.path("schema_valid").asBoolean(false) || rawName.isBlank()) continue;
					JsonNode raw = read(temp.resolve(rawName));
					for (JsonNode item : raw.path("data")) {
						String id = item.path("id").asText("");
						if (id.isBlank()) continue;
						Map<String, String> value = new LinkedHashMap<>();
						value.put("title", item.path("title").asText(""));
						value.put("release_date", item.path("release_date").asText(""));
						value.put("status", item.path("status").asText(""));
						String publication = item.path("release_date").asText("");
						String publicationTime = normalizeMpaNoticeTime(publication);
						String encoded = mapper.writeValueAsString(value);
						out.add(normalizer.normalize(new MaritimeSourceObservation(
								MaritimeSnapshotChangeDetector.MPA_OFFICIAL_NOTICE, "mpa-notice:" + id, provenance,
								publicationTime, retrieved, retrieved, MaritimeSnapshotChangeDetector.LIVE,
								MaritimeSnapshotChangeDetector.UNKNOWN, null, "Singapore", null, null, encoded,
								MaritimeSnapshotChangeDetector.UNKNOWN, null,
								MaritimeSnapshotChangeDetector.UNKNOWN)));
					}
				}
			}
			if (jsonFiles(temp, "*.manifest.json").isEmpty()) throw new IOException("MPA notice collector returned no manifests");
			return out;
		});
	}

	private MaritimeCollectionPoll runNea() {
		return run("NEA_WEATHER", "tools/cdg201/contextual/nea_weather_collect.py", temp -> {
			invoke("tools/cdg201/contextual/nea_weather_collect.py", List.of("--output", temp.toString(),
					"--datasets", "two_hr_forecast",
					"--pause-seconds", "1", "--max-retries", "3"));
			Path normalized = temp.resolve("normalized");
			Map<String, String> retrievalByRaw = new HashMap<>();
			for (Path manifest : jsonFiles(temp, "*.manifest.json")) {
				JsonNode m = read(manifest);
				if (m.path("error").asText("").isBlank()) retrievalByRaw.put(m.path("raw_file").asText(""), m.path("retrieval_completed_utc").asText(m.path("retrieval_started_utc").asText("")));
			}
			if (retrievalByRaw.isEmpty()) throw new IOException("NEA weather collector returned no successful source pages");
			List<MaritimeSnapshot> out = new ArrayList<>();
			if (Files.isDirectory(normalized)) for (Path csv : jsonlessCsvFiles(normalized)) {
				for (Map<String, String> row : readCsv(csv)) {
					String dataset = row.get("dataset"), forecast = row.get("forecast"), raw = row.get("source_raw_file");
					String sourceTime = "two_hr_forecast".equals(dataset) ? first(row.get("forecast_issuance_timestamp"), row.get("source_timestamp")) : row.get("source_timestamp");
					String retrieved = retrievalByRaw.getOrDefault(raw, "");
					if (sourceTime.isBlank() || retrieved.isBlank()) continue;
					String location = "two_hr_forecast".equals(dataset) ? row.get("area") : "Singapore";
					String identity = "two_hr_forecast".equals(dataset)
							? dataset + ":" + location + ":" + row.get("valid_start")
							: dataset + ":" + row.get("station_id");
					String rawValue = "two_hr_forecast".equals(dataset) ? forecast : row.get("value") + " " + row.get("unit");
					String effectiveFrom = first(row.get("valid_start"), sourceTime);
					String effectiveUntil = row.get("valid_end");
					Instant eventInstant = instant(sourceTime);
					if (eventInstant == null) continue;
					boolean historical = eventInstant.isBefore(Instant.now().minusSeconds(6 * 3600L));
					String freshness = !historical
							? MaritimeSnapshotChangeDetector.APPLICABLE : MaritimeSnapshotChangeDetector.HISTORICAL;
					out.add(normalizer.normalize(new MaritimeSourceObservation(
							MaritimeSnapshotChangeDetector.NEA_WEATHER, "nea:" + identity,
							"tools/cdg201/contextual/nea_weather_collect.py:" + csv.getFileName() + ":" + raw,
							sourceTime, sourceTime, retrieved, MaritimeSnapshotChangeDetector.LIVE, freshness, null,
							location.isBlank() ? "Singapore" : location, effectiveFrom, effectiveUntil, rawValue,
							MaritimeSnapshotChangeDetector.UNKNOWN, MaritimeSnapshotChangeDetector.UNKNOWN,
							MaritimeSnapshotChangeDetector.UNKNOWN)));
				}
			}
			return out;
		});
	}

	private MaritimeCollectionPoll run(String source, String script, Collector collector) {
		for (int attempt = 0; attempt <= retries; attempt++) {
			Path temp = null;
			try {
				if (!Files.isRegularFile(projectDirectory.resolve(script))) throw new IOException("Collector script is missing: " + script);
				temp = Files.createTempDirectory("drift-maritime-collection-");
				List<MaritimeSnapshot> snapshots = collector.collect(temp);
				return new MaritimeCollectionPoll(source, true, List.copyOf(snapshots));
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				logFailure(source, interrupted);
				return new MaritimeCollectionPoll(source, false, List.of());
			} catch (Exception ex) {
				if (attempt == retries) {
					logFailure(source, ex);
					return new MaritimeCollectionPoll(source, false, List.of());
				}
				try { Thread.sleep(Math.min(30_000L, 1_000L << Math.min(attempt, 5))); }
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					logFailure(source, interrupted);
					return new MaritimeCollectionPoll(source, false, List.of());
				}
			} finally {
				if (temp != null) deleteTree(temp);
			}
		}
		return new MaritimeCollectionPoll(source, false, List.of());
	}

	private void invoke(String script, List<String> args) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(); command.add(python); command.add(script); command.addAll(args);
		Process process = new ProcessBuilder(command).directory(projectDirectory.toFile())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			process.waitFor(5, TimeUnit.SECONDS);
			throw new CollectorTimeoutException();
		}
		if (process.exitValue() != 0) throw new CollectorExitException(process.exitValue());
	}

	private static void logFailure(String source, Exception ex) {
		String exit = ex instanceof CollectorExitException collectorExit ? Integer.toString(collectorExit.exitCode())
				: ex instanceof CollectorTimeoutException ? "timeout" : "n/a";
		org.slf4j.LoggerFactory.getLogger(MaritimeSnapshotPollingService.class)
				.warn("Maritime source poll failed source={} exception={} exit={}", source,
						ex.getClass().getSimpleName(), exit);
	}

	private static final class CollectorExitException extends IOException {
		private static final long serialVersionUID = 1L;
		private final int exitCode;
		private CollectorExitException(int exitCode) { this.exitCode = exitCode; }
		private int exitCode() { return exitCode; }
	}

	private static final class CollectorTimeoutException extends IOException {
		private static final long serialVersionUID = 1L;
	}

	private JsonNode read(Path path) throws IOException { return mapper.readTree(path.toFile()); }
	private static List<Path> jsonFiles(Path dir, String glob) throws IOException {
		try (var stream = Files.newDirectoryStream(dir, glob)) { List<Path> files = new ArrayList<>(); stream.forEach(files::add); files.sort(Comparator.naturalOrder()); return files; }
	}
	private static List<Path> jsonlessCsvFiles(Path dir) throws IOException {
		try (var stream = Files.newDirectoryStream(dir, "*.csv")) { List<Path> files = new ArrayList<>(); stream.forEach(files::add); files.sort(Comparator.naturalOrder()); return files; }
	}
	private static void deleteTree(Path root) {
		try (var paths = Files.walk(root)) { paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) { } }); }
		catch (IOException ignored) { }
	}
	private static String firstText(JsonNode node, String... names) { for (String name : names) { String value = node.path(name).asText(""); if (!value.isBlank()) return value; } return ""; }
	private static String arrivalIdentity(JsonNode record) {
		return firstText(record, "locationFrom", "location_from") + ":" + firstText(record, "locationTo", "location_to");
	}
	private static String first(String a, String b) { return a == null || a.isBlank() ? (b == null ? "" : b) : a; }
	private static Instant instant(String value) {
		if (value == null || value.isBlank()) return null;
		try { return Instant.parse(value); } catch (Exception ignored) { }
		try { return OffsetDateTime.parse(value).toInstant(); } catch (Exception ignored) { }
		try { return LocalDateTime.parse(value, MPA_LOCAL_TIME).atZone(SINGAPORE).toInstant(); } catch (Exception ignored) { }
		return null;
	}
	static String normalizeMpaTime(String value) {
		Instant parsed = instant(value);
		return parsed == null ? null : formatMpaTimestamp(parsed);
	}
	private static String formatMpaTimestamp(Instant value) {
		return MPA_OFFSET_TIME.format(value.atZone(SINGAPORE));
	}
	static String normalizeMpaNoticeTime(String value) {
		if (value == null || value.isBlank()) return null;
		try { return MPA_OFFSET_TIME.format(LocalDate.parse(value).atStartOfDay(SINGAPORE)); }
		catch (Exception ignored) { return normalizeMpaTime(value); }
	}
	private static List<Map<String, String>> readCsv(Path path) throws IOException {
		List<Map<String, String>> rows = new ArrayList<>();
		try (var reader = Files.newBufferedReader(path)) {
			String headerLine = reader.readLine(); if (headerLine == null) return rows;
			List<String> headers = csvLine(headerLine); String line;
			while ((line = reader.readLine()) != null) { List<String> values = csvLine(line); Map<String, String> row = new HashMap<>();
				for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), i < values.size() ? values.get(i) : ""); rows.add(row); }
		}
		return rows;
	}
	private static List<String> csvLine(String line) {
		List<String> fields = new ArrayList<>(); StringBuilder value = new StringBuilder(); boolean quoted = false;
		for (int i = 0; i < line.length(); i++) { char c = line.charAt(i);
			if (quoted && c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { value.append('"'); i++; }
			else if (c == '"') quoted = !quoted;
			else if (c == ',' && !quoted) { fields.add(value.toString()); value.setLength(0); }
			else value.append(c);
		}
		fields.add(value.toString()); return fields;
	}

	@FunctionalInterface private interface Collector { List<MaritimeSnapshot> collect(Path temp) throws Exception; }
}
