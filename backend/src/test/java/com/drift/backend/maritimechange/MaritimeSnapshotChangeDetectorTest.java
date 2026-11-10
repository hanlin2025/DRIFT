package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class MaritimeSnapshotChangeDetectorTest {

	private static final Instant RECORDED = Instant.parse("2026-11-02T00:00:00Z");
	private static final String MOTHER = "9351048";

	private final Memory memory = new Memory();
	private final MaritimeSnapshotChangeDetector detector = new MaritimeSnapshotChangeDetector(memory,
			Clock.fixed(RECORDED, ZoneOffset.UTC));

	@Test
	void aNewLiveRevisionIsOneForwardChangeAndARepeatIsNot() {
		MaritimeSnapshotChangeBatch first = detector.accept(List.of(revision("2026-10-26T15:46:03Z", "2026-10-28 23:00:00", "2026-10-29 02:00:00")));

		assertThat(first.rejections()).isEmpty();
		assertThat(first.changes()).singleElement().satisfies(change -> {
			assertThat(change.kind()).isEqualTo("NEW");
			assertThat(change.ordering()).isEqualTo("FORWARD");
			assertThat(change.forwardLooking()).isTrue();
			assertThat(change.sourceMode()).isEqualTo("LIVE");
			assertThat(change.freshness()).isEqualTo("APPLICABLE");
			assertThat(change.sourceType()).isEqualTo("MPA_ETA_REVISION");
			assertThat(change.sourceEventAt()).isEqualTo("2026-10-29 02:00:00");
			assertThat(change.retrievedAtUtc()).isEqualTo("2026-10-26T15:46:03Z");
			assertThat(change.provenance()).isEqualTo("MPA OCEANS-X Vessels Due to Arrive");
			assertThat(change.recordedAt()).isEqualTo(RECORDED);
			assertThat(change.contentFingerprint()).hasSize(64);
		});
		assertThat(memory.headCount("mpa:9351048:SIN:SIN")).isEqualTo(1);

		MaritimeSnapshotChangeBatch repeat = detector.accept(List.of(
				revision("2026-10-26T16:46:03Z", "2026-10-28 23:00:00", "2026-10-29 02:00:00")));

		assertThat(repeat.changes()).isEmpty();
		assertThat(repeat.rejections()).isEmpty();
		assertThat(memory.size()).isEqualTo(1);
	}

	@Test
	void oneBatchKeepsASingleCopyOfTheSameFingerprint() {
		MaritimeSnapshot revision = revision("2026-10-26T15:46:03Z", "2026-10-28 23:00:00", "2026-10-29 02:00:00");

		MaritimeSnapshotChangeBatch batch = detector.accept(List.of(revision, revision));

		assertThat(batch.changes()).hasSize(1);
		assertThat(memory.size()).isEqualTo(1);
	}

	@Test
	void aRevisedExpectedTimeMovesTheHeadAndKeepsTheEarlierFingerprint() {
		detector.accept(List.of(revision("2026-10-26T15:46:03Z", "2026-10-28 23:00:00", "2026-10-29 02:00:00")));

		MaritimeSnapshotChangeBatch revised = detector.accept(List.of(
				revision("2026-10-26T17:46:03Z", "2026-10-29 02:00:00", "2026-10-29 05:00:00")));

		assertThat(revised.changes()).singleElement().satisfies(change -> {
			assertThat(change.kind()).isEqualTo("REVISED");
			assertThat(change.ordering()).isEqualTo("FORWARD");
			assertThat(change.forwardLooking()).isTrue();
			assertThat(change.previousValue()).isEqualTo("2026-10-29 02:00:00");
			assertThat(change.currentValue()).isEqualTo("2026-10-29 05:00:00");
		});
		assertThat(memory.size()).isEqualTo(2);
		assertThat(memory.headCount("mpa:9351048:SIN:SIN")).isEqualTo(1);
		assertThat(memory.headValue("mpa:9351048:SIN:SIN")).isEqualTo("2026-10-29 05:00:00");
	}

	@Test
	void aRepeatedTransitionAfterARevertGetsAUniqueFingerprint() {
		MaritimeSnapshot ten = revision("2026-10-26T15:46:03Z", "", "10:00");
		MaritimeSnapshot twelve = revision("2026-10-26T16:46:03Z", "10:00", "12:00");
		MaritimeSnapshot tenAgain = revision("2026-10-26T17:46:03Z", "12:00", "10:00");
		MaritimeSnapshot twelveAgain = revision("2026-10-26T18:46:03Z", "10:00", "12:00");

		MaritimeSnapshotChangeBatch first = detector.accept(List.of(ten));
		assertThat(detector.accept(List.of(ten)).changes()).isEmpty();
		MaritimeSnapshotChangeBatch second = detector.accept(List.of(twelve));
		assertThat(detector.accept(List.of(twelve)).changes()).isEmpty();
		MaritimeSnapshotChangeBatch third = detector.accept(List.of(tenAgain));
		assertThat(detector.accept(List.of(tenAgain)).changes()).isEmpty();
		MaritimeSnapshotChangeBatch fourth = detector.accept(List.of(twelveAgain));

		assertThat(List.of(first, second, third, fourth)).extracting(batch -> batch.changes().size())
				.containsExactly(1, 1, 1, 1);
		assertThat(fourth.changes()).singleElement().satisfies(change -> {
			assertThat(change.previousValue()).isEqualTo("10:00");
			assertThat(change.currentValue()).isEqualTo("12:00");
			assertThat(change.contentFingerprint()).isNotEqualTo(second.changes().getFirst().contentFingerprint());
		});
		assertThat(memory.headValue("mpa:9351048:SIN:SIN")).isEqualTo("12:00");
		assertThat(memory.size()).isEqualTo(4);
	}

	@Test
	void anOlderRetrievalDoesNotReplaceTheHeadAndRepeatsStayQuiet() {
		detector.accept(List.of(revision("2026-10-26T17:46:03Z", "2026-10-28 23:00:00", "2026-10-29 05:00:00")));
		MaritimeSnapshot older = revision("2026-10-26T15:46:03Z", "2026-10-28 20:00:00", "2026-10-28 23:00:00");

		MaritimeSnapshotChangeBatch late = detector.accept(List.of(older));
		MaritimeSnapshotChangeBatch again = detector.accept(List.of(older));

		assertThat(late.changes()).singleElement().satisfies(change -> {
			assertThat(change.ordering()).isEqualTo("OUT_OF_ORDER");
			assertThat(change.forwardLooking()).isFalse();
			assertThat(change.freshness()).isEqualTo("HISTORICAL");
			assertThat(change.sourceMode()).isEqualTo("LIVE");
			assertThat(change.previousValue()).isNull();
		});
		assertThat(again.changes()).isEmpty();
		assertThat(memory.headValue("mpa:9351048:SIN:SIN")).isEqualTo("2026-10-29 05:00:00");
		assertThat(memory.size()).isEqualTo(2);
	}

	@Test
	void aHistoricalBackfillIsNotALiveForwardEventAndDoesNotReplaceANewerHead() {
		MaritimeSnapshotChangeBatch backfill = detector.accept(List.of(backfill("2026-10-20T00:00:00Z", "2026-10-28 23:00:00")));

		assertThat(backfill.changes()).singleElement().satisfies(change -> {
			assertThat(change.sourceMode()).isEqualTo("HISTORICAL_BACKFILL");
			assertThat(change.ordering()).isEqualTo("BACKFILL");
			assertThat(change.forwardLooking()).isFalse();
			assertThat(change.freshness()).isEqualTo("HISTORICAL");
			assertThat(change.kind()).isEqualTo("NEW");
		});

		detector.accept(List.of(revision("2026-10-26T15:46:03Z", "2026-10-28 23:00:00", "2026-10-29 02:00:00")));
		MaritimeSnapshotChangeBatch olderBackfill = detector.accept(List.of(
				backfill("2026-10-19T00:00:00Z", "2026-10-27 23:00:00")));

		assertThat(olderBackfill.changes()).singleElement().satisfies(change -> {
			assertThat(change.ordering()).isEqualTo("BACKFILL");
			assertThat(change.forwardLooking()).isFalse();
			assertThat(change.sourceMode()).isEqualTo("HISTORICAL_BACKFILL");
		});
		assertThat(memory.headValue("mpa:9351048:SIN:SIN")).isEqualTo("2026-10-29 02:00:00");
	}

	@Test
	void aLaterRetrievedOlderHistoricalEventDoesNotReplaceTheHead() {
		detector.accept(List.of(backfill("2026-11-01T00:00:00Z", "2026-11-01T00:00:00Z")));
		MaritimeSnapshot lateBackfill = backfill("2026-11-02T00:00:00Z", "2026-10-31T00:00:00Z");

		MaritimeSnapshotChangeBatch batch = detector.accept(List.of(lateBackfill));

		assertThat(batch.changes()).singleElement().satisfies(change -> {
			assertThat(change.ordering()).isEqualTo("BACKFILL");
			assertThat(change.forwardLooking()).isFalse();
		});
		assertThat(memory.headValue("mpa:9351048:SIN:SIN")).isEqualTo("2026-11-01T00:00:00Z");
	}

	@Test
	void aRecordedReplayKeepsItsSourceModeAndIsNotForwardLooking() {
		MaritimeSnapshotChangeBatch replay = detector.accept(List.of(new MaritimeSnapshot(
				"MPA_ETA_REVISION", "mpa:9351048:SIN:SIN", "Manifest due_to_arrive_20261026.manifest.json",
				"2026-10-29 02:00:00", "2026-10-26T15:46:03.213897Z", "2026-10-26T15:46:03.213897Z",
				"RECORDED_REPLAY", null, MOTHER, "Singapore", null, null, "2026-10-28 23:00:00", "2026-10-29 02:00:00",
				null, null, null)));

		assertThat(replay.changes()).singleElement().satisfies(change -> {
			assertThat(change.sourceMode()).isEqualTo("RECORDED_REPLAY");
			assertThat(change.forwardLooking()).isFalse();
			assertThat(change.freshness()).isEqualTo("UNKNOWN");
			assertThat(change.sourceEventAt()).isEqualTo("2026-10-29 02:00:00");
			assertThat(change.observedAt()).isEqualTo("2026-10-26T15:46:03.213897Z");
		});
	}

	@Test
	void anOlderSourceObservationCollectedLiveIsHistoricalButRetainsLiveMode() {
		MaritimeSnapshotChangeBatch batch = detector.accept(List.of(new MaritimeSnapshot(
				"NEA_WEATHER", "nea:rain:S306", "NEA live collector", "2026-10-20T00:00:00Z",
				"2026-10-20T00:00:00Z", "2026-11-01T00:00:00Z", "LIVE", "HISTORICAL", null,
				"Singapore", null, null, null, "0.2 mm", "UNKNOWN", "UNKNOWN", "UNKNOWN")));

		assertThat(batch.rejections()).isEmpty();
		assertThat(batch.changes()).singleElement().satisfies(change -> {
			assertThat(change.sourceMode()).isEqualTo("LIVE");
			assertThat(change.freshness()).isEqualTo("HISTORICAL");
			assertThat(change.forwardLooking()).isFalse();
		});
	}

	@Test
	void aNoticeAndAWeatherAreaUseTheSameFingerprintAcrossRetrievals() {
		MaritimeSnapshot notice = notice("2026-10-27T00:05:00Z", "PMN 12/2026", "Tuas closed");
		MaritimeSnapshot noticeAgain = notice("2026-10-28T00:05:00Z", "PMN 12/2026", "Tuas closed");
		MaritimeSnapshot weather = weather("2026-10-27T01:00:00Z", "West Coast", "Showers");
		MaritimeSnapshot weatherAgain = weather("2026-10-27T02:00:00Z", "West Coast", "Showers");
		MaritimeSnapshot weatherChanged = weather("2026-10-27T03:00:00Z", "West Coast", "Thunderstorm");

		assertThat(detector.accept(List.of(notice)).changes()).hasSize(1);
		assertThat(detector.accept(List.of(noticeAgain)).changes()).isEmpty();
		assertThat(detector.accept(List.of(weather)).changes()).singleElement().satisfies(change -> {
			assertThat(change.sourceType()).isEqualTo("NEA_WEATHER");
			assertThat(change.location()).isEqualTo("West Coast");
		});
		assertThat(detector.accept(List.of(weatherAgain)).changes()).isEmpty();
		assertThat(detector.accept(List.of(weatherChanged)).changes()).singleElement()
				.extracting(MaritimeSnapshotChange::kind).isEqualTo("REVISED");
	}

	@Test
	void aMalformedSnapshotIsRefusedAndTheNextValidSnapshotIsKept() {
		MaritimeSnapshot valid = revision("2026-10-26T15:46:03Z", "2026-10-28 23:00:00", "2026-10-29 02:00:00");

		MaritimeSnapshotChangeBatch batch = detector.accept(Arrays.asList(
				null,
				revision("not-a-timestamp", "2026-10-28 23:00:00", "2026-10-29 02:00:00"),
				new MaritimeSnapshot("MPA_ETA_REVISION", "mpa:bad", "MPA", "2026-10-29 02:00:00", null,
						"2026-10-26T15:46:03Z", "LIVE", null, "9387421", "Singapore", null, null, null, "2026-10-29 02:00:00",
						null, null, null),
				new MaritimeSnapshot("MPA_OFFICIAL_NOTICE", " ", "MPA", null, null, "2026-10-27T00:00:00Z", "LIVE", null,
						null, "Tuas", null, null, null, "closed", null, null, null),
				new MaritimeSnapshot("NEA_WEATHER", "nea:rain", "NEA", null, null, "2026-10-27T00:00:00Z", "LIVE", null,
						null, " ", null, null, null, "Rain", null, null, null),
				new MaritimeSnapshot("MPA_OFFICIAL_NOTICE", "PMN 1", "MPA", null, null, "2026-10-27T00:00:00Z", "LIVE", null,
						null, "Tuas", "2026-10-31T00:00:00Z", "2026-10-01T00:00:00Z", null, "closed", null, null, null),
				new MaritimeSnapshot("OTHER", "x", "MPA", null, null, "2026-10-27T00:00:00Z", "LIVE", null, null, null,
						null, null, null, "x", null, null, null),
				valid));

		assertThat(batch.rejections()).extracting(MaritimeSnapshotRejection::reason)
				.containsExactly("SNAPSHOT", "RETRIEVED_AT", "IMO", "SOURCE_IDENTITY", "LOCATION",
						"EFFECTIVE_PERIOD", "SOURCE_TYPE");
		assertThat(batch.changes()).hasSize(1);
		assertThat(memory.size()).isEqualTo(1);
	}

	@Test
	void refusesAMissingProvenanceContentAndAnUnknownCompatibility() {
		MaritimeSnapshotChangeBatch batch = detector.accept(List.of(
				new MaritimeSnapshot("MPA_ETA_REVISION", "mpa:9351048", " ", null, null, "2026-10-26T15:46:03Z", "LIVE",
						null, MOTHER, "Singapore", null, null, null, "2026-10-29 02:00:00", null, null, null),
				new MaritimeSnapshot("MPA_OFFICIAL_NOTICE", "PMN 4", "MPA notices", null, null, "2026-10-27T00:00:00Z",
						"LIVE", "FRESH", null, "Tuas", null, null, null, "closed", null, null, null),
				new MaritimeSnapshot("NEA_WEATHER", "nea:city", "NEA", null, null, "2026-10-27T00:00:00Z", "LIVE_SNAPSHOT",
						null, null, "City", null, null, null, "Fair", null, null, null),
				new MaritimeSnapshot("MPA_ETA_REVISION", "mpa:9351048", "MPA", null, null, "2026-10-26T15:46:03Z", "LIVE",
						null, MOTHER, "Singapore", null, null, null, null, "MAYBE", null, null)));

		assertThat(batch.rejections()).extracting(MaritimeSnapshotRejection::reason)
				.containsExactly("PROVENANCE", "FRESHNESS", "SOURCE_MODE", "CONTENT");
		assertThat(batch.changes()).isEmpty();
		assertThat(memory.size()).isZero();
	}

	@Test
	void aNullBatchIsRejected() {
		assertThatThrownBy(() -> detector.accept(null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void theDetectorHasNoCollectorPath() {
		assertThat(MaritimeSnapshotChangeDetector.class.getDeclaredFields())
				.extracting(field -> field.getType().getName())
				.noneMatch(name -> name.contains("Path") || name.contains("File"));
	}

	private static MaritimeSnapshot revision(String retrievedAt, String previous, String current) {
		return new MaritimeSnapshot("MPA_ETA_REVISION", "mpa:9351048:SIN:SIN", "MPA OCEANS-X Vessels Due to Arrive",
				current, retrievedAt, retrievedAt, "LIVE", null, MOTHER, "Singapore", null, null, previous, current,
				null, null, null);
	}

	private static MaritimeSnapshot backfill(String retrievedAt, String current) {
		return new MaritimeSnapshot("MPA_ETA_REVISION", "mpa:9351048:SIN:SIN", "MPA historical collection",
				current, retrievedAt, retrievedAt, "HISTORICAL_BACKFILL", "HISTORICAL", MOTHER, "Singapore", null, null,
				null, current, null, null, null);
	}

	private static MaritimeSnapshot notice(String retrievedAt, String identity, String current) {
		return new MaritimeSnapshot("MPA_OFFICIAL_NOTICE", identity, "MPA Port Marine Notices", identity, null,
				retrievedAt, "LIVE", null, null, "Tuas", "2026-10-01T00:00:00Z", "2026-10-31T00:00:00Z", null, current,
				"UNKNOWN", null, null);
	}

	private static MaritimeSnapshot weather(String retrievedAt, String area, String forecast) {
		return new MaritimeSnapshot("NEA_WEATHER", "nea:two_hr:" + area + ":2026-10-27T00:00:00Z", "NEA public weather API",
				"2026-10-27T00:00:00+08:00", null, retrievedAt, "LIVE", null, null, area, "2026-10-27T00:00:00Z",
				"2026-10-27T02:00:00Z", null, forecast, null, null, null);
	}

	private static final class Memory implements MaritimeSnapshotObservationStore {
		private final List<MaritimeSnapshotObservation> rows = new ArrayList<>();

		@Override
		public Optional<MaritimeSnapshotObservation> head(String sourceType, String sourceIdentity) {
			return rows.stream()
					.filter(row -> row.isHead() && row.getSourceType().equals(sourceType) && row.getSourceIdentity().equals(sourceIdentity))
					.findFirst();
		}

		@Override
		public Optional<MaritimeSnapshotObservation> find(String sourceType, String sourceIdentity, String contentFingerprint) {
			return rows.stream()
					.filter(row -> row.getSourceType().equals(sourceType) && row.getSourceIdentity().equals(sourceIdentity)
							&& row.getContentFingerprint().equals(contentFingerprint))
					.findFirst();
		}

		@Override
		public void add(MaritimeSnapshotObservation observation, boolean becomesHead) {
			if (find(observation.getSourceType(), observation.getSourceIdentity(), observation.getContentFingerprint()).isPresent()) {
				throw new IllegalStateException("duplicate fingerprint");
			}
			if (becomesHead) {
				rows.stream()
						.filter(row -> row.isHead() && row.getSourceType().equals(observation.getSourceType())
								&& row.getSourceIdentity().equals(observation.getSourceIdentity()))
						.forEach(MaritimeSnapshotObservation::clearHead);
				observation.markHead();
			}
			rows.add(observation);
		}

		private int size() {
			return rows.size();
		}

		private int headCount(String identity) {
			return (int) rows.stream().filter(row -> row.isHead() && row.getSourceIdentity().equals(identity)).count();
		}

		private String headValue(String identity) {
			return rows.stream()
					.filter(row -> row.isHead() && row.getSourceIdentity().equals(identity))
					.findFirst()
					.orElseThrow()
					.getCurrentValue();
		}
	}
}
