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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
	private static final Pattern SECRET_ASSIGNMENT = Pattern.compile("(?i)((?:api[_-]?key|access[_-]?token|token)\\s*[=:]\\s*|authorization\\s*[=:]\\s*(?:bearer\\s+)?)[^\\s,;]+");
	private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(https?://)[^/@\\s]+@");
	private static final Pattern URL_QUERY_SECRET = Pattern.compile("(?i)([?&](?:api[_-]?key|token|key)=)[^&\\s]+");
	static final String NEA_TWO_HOUR_FORECAST = "two_hr_forecast";
	/** NEA forecast areas that contain Singapore's container and multipurpose terminals. */
	static final String DEFAULT_NEA_AREAS = "Tuas,Pioneer,Queenstown,Bukit Merah,Southern Islands";

	private final ObjectMapper mapper;
	private final MaritimeSnapshotNormalizer normalizer;
	private final Path projectDirectory;
	private final String python;
	private final long timeoutSeconds;
	private final int retries;
	private final int lookbackDays;
	private final int noticeLookbackDays;
	private final Set<String> neaAreas;

	MaritimeSnapshotPollingService(ObjectMapper mapper, MaritimeSnapshotNormalizer normalizer,
			@Value("${app.maritime-change.collection.project-directory:.}") String projectDirectory,
			@Value("${app.maritime-change.collection.python:python3}") String python,
			@Value("${app.maritime-change.collection.source-timeout-seconds:300}") long timeoutSeconds,
			@Value("${app.maritime-change.collection.retries:1}") int retries,
			@Value("${app.maritime-change.collection.mpa-lookback-days:3}") int lookbackDays,
			@Value("${app.maritime-change.collection.notice-lookback-days:14}") int noticeLookbackDays,
			@Value("${app.maritime-change.collection.nea-areas:" + DEFAULT_NEA_AREAS + "}") String neaAreas) {
		this.mapper = mapper;
		this.normalizer = normalizer;
		this.projectDirectory = Path.of(projectDirectory).toAbsolutePath().normalize();
		this.python = python;
		this.timeoutSeconds = Math.max(30, Math.min(300, timeoutSeconds));
		this.retries = Math.max(0, Math.min(2, retries));
		this.lookbackDays = Math.max(0, Math.min(14, lookbackDays));
		this.noticeLookbackDays = Math.max(1, Math.min(60, noticeLookbackDays));
		this.neaAreas = areaSet(neaAreas);
	}

	List<MaritimeCollectionPoll> poll() {
		return List.of(runMpaExpected(), runNotices(), runNea());
	}

	private MaritimeCollectionPoll runMpaExpected() {
		return run("MPA_EXPECTED_ARRIVALS", "tools/cdg201/mpa/mpa_oceansx_collect.py", temp -> {
			List<String> args = List.of("--rolling", "--feed-group", "expected", "--lookback-days",
					Integer.toString(lookbackDays), "--lookahead-days", "3", "--output", temp.toString());
			invoke("tools/cdg201/mpa/mpa_oceansx_collect.py", args, temp);
			Instant retrieved = Instant.now();
			List<MaritimeSnapshot> out = new ArrayList<>();
			Map<String, Long> rejectedRecords = new HashMap<>();
			long sourceRecordCount = 0;
			for (Path manifest : jsonFiles(temp, "due_to_arrive_*.manifest.json")) {
				JsonNode m = read(manifest);
				// An empty OCEANS-X day is HTTP 204 with no body. Keep the other days.
				if (emptyExpectedArrivalDay(m)) continue;
				if (!m.path("schema_valid").asBoolean(false) || !m.path("error").asText("").isBlank()
						|| m.path("http_status").asInt() != 200) {
					throw new IOException("MPA expected-arrival page rejected http_status="
							+ m.path("http_status").asText("unknown") + " error="
							+ safeDiagnostic(m.path("error").asText("schema-invalid")));
				}
				String rawName = m.path("raw_file").asText("");
				if (rawName.isBlank()) throw new IOException("MPA expected-arrival collector returned no raw page");
				JsonNode records = read(temp.resolve(rawName));
				if (!records.isArray()) throw new IOException("MPA expected-arrival response is not a record list");
				sourceRecordCount += records.size();
				String retrievedAt = m.path("retrieval_completed_utc").asText(m.path("retrieval_started_utc").asText(retrieved.toString()));
				String provenance = "tools/cdg201/mpa/mpa_oceansx_collect.py:" + manifest.getFileName();
				for (JsonNode record : records) {
					JsonNode vessel = record.path("vesselParticulars");
					String imo = firstText(vessel, "imoNumber", "imo");
					String arrival = firstText(record, "duetoArriveTime", "dueToArriveTime", "due_to_arrive_time");
					if (imo.isBlank()) { rejectedRecords.merge("IMO_MISSING", 1L, Long::sum); continue; }
					if (arrival.isBlank()) { rejectedRecords.merge("ARRIVAL_MISSING", 1L, Long::sum); continue; }
					String location = firstText(record, "locationTo", "location_to", "port");
					String departure = firstText(record, "duetoDepartTime", "dueToDepartTime", "due_to_depart_time");
					Instant eventInstant = instant(arrival);
					if (eventInstant == null) { rejectedRecords.merge("ARRIVAL_TIMESTAMP", 1L, Long::sum); continue; }
					String normalizedArrival = formatMpaTimestamp(eventInstant);
					Instant departureInstant = instant(departure);
					String normalizedDeparture = departureInstant == null ? null : formatMpaTimestamp(departureInstant);
					boolean historical = eventInstant.isBefore(Instant.now());
					String freshness = !historical
							? MaritimeSnapshotChangeDetector.APPLICABLE : MaritimeSnapshotChangeDetector.HISTORICAL;
					String value = "arrival=" + arrival + ";departure=" + departure + ";port=" + location;
					MaritimeSourceObservation source = new MaritimeSourceObservation(
							MaritimeSnapshotChangeDetector.MPA_ETA_REVISION,
							"mpa:" + imo + ":" + arrivalIdentity(record, eventInstant),
							provenance, normalizedArrival, retrievedAt, retrievedAt, MaritimeSnapshotChangeDetector.LIVE, freshness,
							imo, location.isBlank() ? "Singapore" : location, normalizedArrival, normalizedDeparture,
							value, null, null, null);
					out.add(normalizer.normalize(source));
				}
			}
			if (jsonFiles(temp, "due_to_arrive_*.manifest.json").isEmpty()) {
				throw new IOException("MPA expected-arrival collector returned no source manifests");
			}
			logMappingRejections("MPA_EXPECTED_ARRIVALS", rejectedRecords);
			if (sourceRecordCount > 0 && out.isEmpty() && !rejectedRecords.isEmpty()) {
				throw new IOException("MPA expected-arrival page contained no valid arrival records");
			}
			return out;
		});
	}

	private MaritimeCollectionPoll runNotices() {
		return run("MPA_OFFICIAL_NOTICES", "tools/maritime_notices/mpa_official_notices_collect.py", temp -> {
			invoke("tools/maritime_notices/mpa_official_notices_collect.py", List.of("--raw-output", temp.toString(),
					"--year", Integer.toString(java.time.Year.now(ZoneOffset.UTC).getValue()), "--limit", "100",
					"--observation-mode", "prospective"), temp);
			return mapNoticeSnapshots(temp, Instant.now());
		});
	}

	/** Maps the official notice collector's run manifest and its nested page manifests. */
	List<MaritimeSnapshot> mapNoticeSnapshots(Path directory, Instant now) throws IOException {
		List<Path> manifests = jsonFiles(directory, "*.manifest.json");
		if (manifests.isEmpty()) throw new IOException("MPA notice collector returned no manifests");
		List<MaritimeSnapshot> out = new ArrayList<>();
		for (Path manifest : manifests) {
			JsonNode run = read(manifest);
			if (!run.path("schema_valid").asBoolean(false) || !run.path("valid_coverage").asBoolean(false)
					|| run.path("http_status").asInt() != 200 || !run.path("error").asText("").isBlank()) {
				throw new IOException("MPA notice collector returned an incomplete listing");
			}
			String retrieved = run.path("retrieval_completed_utc")
					.asText(run.path("retrieval_started_utc").asText(now.toString()));
			String provenance = "tools/maritime_notices/mpa_official_notices_collect.py:" + manifest.getFileName();
			JsonNode pages = run.path("pages");
			if (!pages.isArray() || pages.isEmpty()) throw new IOException("MPA notice collector returned no pages");
			for (JsonNode page : pages) {
				String rawName = page.path("raw_file").asText("");
				if (page.path("http_status").asInt() != 200 || !page.path("schema_valid").asBoolean(false)
						|| rawName.isBlank()) {
					throw new IOException("MPA notice collector returned a failed page");
				}
				JsonNode raw = read(directory.resolve(rawName));
				JsonNode data = raw.path("data");
				if (!data.isArray()) throw new IOException("MPA notice response is not a record list");
				for (JsonNode item : data) {
					String id = item.path("id").asText("");
					if (id.isBlank()) continue;
					Map<String, String> value = new LinkedHashMap<>();
					value.put("title", item.path("title").asText(""));
					value.put("release_date", item.path("release_date").asText(""));
					value.put("status", item.path("status").asText(""));
					String publicationTime = normalizeMpaNoticeTime(item.path("release_date").asText(""));
					if (!noticeWithinLookback(publicationTime, now, noticeLookbackDays)) continue;
					String encoded = mapper.writeValueAsString(value);
					out.add(normalizer.normalize(new MaritimeSourceObservation(
							MaritimeSnapshotChangeDetector.MPA_OFFICIAL_NOTICE, "mpa-notice:" + id, provenance,
							publicationTime, retrieved, retrieved, MaritimeSnapshotChangeDetector.LIVE,
							MaritimeSnapshotChangeDetector.UNKNOWN, null, "Singapore", null, null, encoded,
							null, null, null)));
				}
			}
		}
		return List.copyOf(out);
	}

	private MaritimeCollectionPoll runNea() {
		return run("NEA_WEATHER", "tools/cdg201/contextual/nea_weather_collect.py", temp -> {
			invoke("tools/cdg201/contextual/nea_weather_collect.py", List.of("--output", temp.toString(),
					"--datasets", "two_hr_forecast",
					"--pause-seconds", "1", "--max-retries", "3"), temp);
			Path normalized = temp.resolve("normalized");
			Map<String, String> retrievalByRaw = new HashMap<>();
			List<Path> manifests = jsonFiles(temp, "*.manifest.json");
			for (Path manifest : manifests) {
				JsonNode m = read(manifest);
				String raw = m.path("raw_file").asText("");
				if (!m.path("error").asText("").isBlank() || m.path("http_status").asInt() != 200 || raw.isBlank()) {
					throw new IOException("NEA weather collector returned an incomplete source page");
				}
				retrievalByRaw.put(raw, m.path("retrieval_completed_utc").asText(m.path("retrieval_started_utc").asText("")));
			}
			if (retrievalByRaw.isEmpty()) throw new IOException("NEA weather collector returned no source pages");
			List<MaritimeSourceObservation> observations = new ArrayList<>();
			if (Files.isDirectory(normalized)) for (Path csv : jsonlessCsvFiles(normalized)) {
				observations.addAll(neaForecastObservations(readCsv(csv), retrievalByRaw,
						"tools/cdg201/contextual/nea_weather_collect.py:" + csv.getFileName(), neaAreas, Instant.now()));
			}
			// Oldest issuance first, so a later forecast for the same area becomes the head.
			observations.sort(Comparator.comparing(observation -> instant(observation.sourceEventAt())));
			List<MaritimeSnapshot> out = observations.stream().map(normalizer::normalize).toList();
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

	private void invoke(String script, List<String> args, Path temp) throws IOException, InterruptedException {
		List<String> command = new ArrayList<>(); command.add(python); command.add(script); command.addAll(args);
		Path stderr = temp.resolve("collector-stderr.log");
		Process process = new ProcessBuilder(command).directory(projectDirectory.toFile())
				.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(stderr.toFile()).start();
		if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			process.waitFor(5, TimeUnit.SECONDS);
			throw new CollectorTimeoutException(readDiagnostic(stderr));
		}
		if (process.exitValue() != 0) throw new CollectorExitException(process.exitValue(), readDiagnostic(stderr));
	}

	private static void logFailure(String source, Exception ex) {
		String exit = ex instanceof CollectorExitException collectorExit ? Integer.toString(collectorExit.exitCode())
				: ex instanceof CollectorTimeoutException ? "timeout" : "n/a";
		String diagnostic = ex instanceof CollectorExitException collectorExit ? collectorExit.diagnostic()
				: ex instanceof CollectorTimeoutException timeout ? timeout.diagnostic()
				: safeDiagnostic(ex.getMessage());
		org.slf4j.LoggerFactory.getLogger(MaritimeSnapshotPollingService.class)
				.warn("Maritime source poll failed source={} exception={} exit={} diagnostic={}", source,
						ex.getClass().getSimpleName(), exit, diagnostic);
	}

	private static String readDiagnostic(Path stderr) {
		try (var input = Files.newInputStream(stderr)) {
			byte[] bytes = input.readNBytes(1024);
			return safeDiagnostic(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
		} catch (IOException ignored) { return ""; }
	}

	private static String safeDiagnostic(String diagnostic) {
		if (diagnostic == null || diagnostic.isBlank()) return "";
		String oneLine = diagnostic.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\s{2,}", " ").trim();
		oneLine = URL_CREDENTIALS.matcher(oneLine).replaceAll("$1[redacted]@");
		oneLine = URL_QUERY_SECRET.matcher(oneLine).replaceAll("$1[redacted]");
		oneLine = SECRET_ASSIGNMENT.matcher(oneLine).replaceAll("$1[redacted]");
		return oneLine.substring(0, Math.min(240, oneLine.length()));
	}

	private static void logMappingRejections(String source, Map<String, Long> reasons) {
		if (!reasons.isEmpty()) {
			org.slf4j.LoggerFactory.getLogger(MaritimeSnapshotPollingService.class)
					.warn("Maritime source records rejected during mapping source={} rejected={} reasons={}",
							source, reasons.values().stream().mapToLong(Long::longValue).sum(), reasons);
		}
	}

	private static final class CollectorExitException extends IOException {
		private static final long serialVersionUID = 1L;
		private final int exitCode;
		private final String diagnostic;
		private CollectorExitException(int exitCode, String diagnostic) { this.exitCode = exitCode; this.diagnostic = diagnostic; }
		private int exitCode() { return exitCode; }
		private String diagnostic() { return diagnostic; }
	}

	private static final class CollectorTimeoutException extends IOException {
		private static final long serialVersionUID = 1L;
		private final String diagnostic;
		private CollectorTimeoutException(String diagnostic) { this.diagnostic = diagnostic; }
		private String diagnostic() { return diagnostic; }
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
	/**
	 * Maps NEA two-hour forecast rows for the configured port areas. Each area is one source identity
	 * whose value is the forecast text, so a reissued but unchanged forecast is not a new event and
	 * only an actual change in the area's forecast is recorded.
	 */
	static List<MaritimeSourceObservation> neaForecastObservations(List<Map<String, String>> rows,
			Map<String, String> retrievalByRaw, String provenancePrefix, Set<String> areas, Instant now) {
		List<MaritimeSourceObservation> out = new ArrayList<>();
		for (Map<String, String> row : rows) {
			if (!NEA_TWO_HOUR_FORECAST.equals(row.get("dataset"))) continue;
			String area = first(row.get("area"), "").trim();
			String forecast = first(row.get("forecast"), "").trim();
			if (area.isEmpty() || forecast.isEmpty() || !areas.contains(areaKey(area))) continue;
			String sourceTime = first(row.get("forecast_issuance_timestamp"), row.get("source_timestamp"));
			String raw = first(row.get("source_raw_file"), "");
			String retrieved = retrievalByRaw.getOrDefault(raw, "");
			Instant eventInstant = instant(sourceTime);
			if (eventInstant == null || retrieved.isBlank()) continue;
			Instant validStart = instant(row.get("valid_start"));
			Instant validEnd = instant(row.get("valid_end"));
			String effectiveFrom = validStart == null ? null : validStart.toString();
			String effectiveUntil = validEnd == null || (validStart != null && validEnd.isBefore(validStart))
					? null : validEnd.toString();
			boolean historical = eventInstant.isBefore(now.minusSeconds(6 * 3600L));
			String freshness = historical ? MaritimeSnapshotChangeDetector.HISTORICAL : MaritimeSnapshotChangeDetector.APPLICABLE;
			out.add(new MaritimeSourceObservation(MaritimeSnapshotChangeDetector.NEA_WEATHER,
					"nea:" + NEA_TWO_HOUR_FORECAST + ":" + area, provenancePrefix + ":" + raw, sourceTime, sourceTime,
					retrieved, MaritimeSnapshotChangeDetector.LIVE, freshness, null, area, effectiveFrom, effectiveUntil,
					forecast, null, null, null));
		}
		return out;
	}

	/** A subscribed due-to-arrive day with no ships. Any other status remains a failed page. */
	static boolean emptyExpectedArrivalDay(JsonNode manifest) {
		return manifest.path("http_status").asInt() == 204
				&& "HTTPNoContent".equals(manifest.path("error").asText(""))
				&& manifest.path("raw_file").asText("").isBlank();
	}

	/** Only notices released within the lookback are new to a live poll; older listings are not changes. */
	static boolean noticeWithinLookback(String publicationTime, Instant now, int lookbackDays) {
		Instant published = instant(publicationTime);
		return published == null || !published.isBefore(now.minus(java.time.Duration.ofDays(lookbackDays)));
	}

	static Set<String> areaSet(String configured) {
		String areas = configured == null || configured.isBlank() ? DEFAULT_NEA_AREAS : configured;
		return java.util.Arrays.stream(areas.split(",")).map(MaritimeSnapshotPollingService::areaKey)
				.filter(area -> !area.isEmpty()).collect(Collectors.toUnmodifiableSet());
	}

	private static String areaKey(String area) {
		return area.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
	}

	static String arrivalIdentity(JsonNode record, Instant arrival) {
		String callSign = firstText(record.path("vesselParticulars"), "callSign", "call_sign");
		String vesselName = firstText(record.path("vesselParticulars"), "vesselName", "name");
		String vesselKey = callSign.isBlank() ? vesselName : callSign;
		String route = firstText(record, "locationFrom", "location_from") + ":"
				+ firstText(record, "locationTo", "location_to");
		// The saved OCEANS-X schema has no port-call or voyage ID. Including the scheduled
		// arrival date keeps same-vessel/same-route calls distinct; a revision crossing a
		// date boundary is conservatively treated as a separate source identity.
		return vesselKey + ":" + route + ":" + arrival.atZone(SINGAPORE).toLocalDate();
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
