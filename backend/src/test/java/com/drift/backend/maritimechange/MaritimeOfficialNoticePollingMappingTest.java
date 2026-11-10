package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

class MaritimeOfficialNoticePollingMappingTest {
	private static final Instant OBSERVED_AT = Instant.parse("2026-10-27T07:43:25.362956Z");

	@Test
	void readsTheOfficialCollectorsNestedPageManifestAndNormalizesItsAuthenticNotice(@TempDir Path temp) throws Exception {
		Files.writeString(temp.resolve("page-1.json"), """
				{"data":[{"id":"f1ef5da5-2dbb-47aa-8ad4-f565a8e1f489",
				"title":"PORT MARINE CIRCULAR NO. 10 OF 2026 - SUBMISSION OF ACCURATE INFORMATION FOR VESSEL PORT CALLS AND PORT CLEARANCE  ",
				"release_date":"2026-09-02T07:20:00.000Z","status":null}],"meta":{}}
				""");
		Files.writeString(temp.resolve("run.manifest.json"), """
				{"retrieval_started_utc":"2026-10-27T07:43:25.188399+00:00",
				"retrieval_completed_utc":"2026-10-27T07:43:25.362956+00:00",
				"http_status":200,"schema_valid":true,"valid_coverage":true,"error":"",
				"pages":[{"http_status":200,"schema_valid":true,"raw_file":"page-1.json","error":""}]}
				""");
		MaritimeSnapshotPollingService polling = pollingService(temp, 60);
		List<MaritimeSnapshot> snapshots = polling.mapNoticeSnapshots(temp, Instant.parse("2026-10-27T08:00:00Z"));
		MemoryStore store = new MemoryStore();
		MaritimeSnapshotChangeDetector detector = new MaritimeSnapshotChangeDetector(store);

		MaritimeSnapshotChangeBatch first = detector.accept(snapshots);
		MaritimeSnapshotChangeBatch repeated = detector.accept(
				polling.mapNoticeSnapshots(temp, Instant.parse("2026-10-27T08:30:00Z")));

		assertThat(snapshots).singleElement().satisfies(snapshot -> {
			assertThat(snapshot.sourceIdentity()).isEqualTo("mpa-notice:f1ef5da5-2dbb-47aa-8ad4-f565a8e1f489");
			assertThat(snapshot.sourceEventAt()).isEqualTo("2026-09-02T15:20:00+08:00");
			assertThat(snapshot.retrievedAtUtc()).isEqualTo("2026-10-27T07:43:25.362956+00:00");
			assertThat(snapshot.provenance()).contains("run.manifest.json");
			assertThat(snapshot.currentValue()).contains("PORT MARINE CIRCULAR NO. 10 OF 2026");
		});
		assertThat(first.rejections()).isEmpty();
		assertThat(first.changes()).singleElement().extracting(MaritimeSnapshotChange::kind).isEqualTo("NEW");
		assertThat(repeated.changes()).isEmpty();
	}

	@Test
	void aFailedNoticePageIsUnavailableInsteadOfAnEmptyValidPoll(@TempDir Path temp) throws Exception {
		Files.writeString(temp.resolve("run.manifest.json"), """
				{"http_status":200,"schema_valid":true,"valid_coverage":false,"error":"page 2 failed",
				"pages":[{"http_status":503,"schema_valid":false,"raw_file":"","error":"unavailable"}]}
				""");

		assertThatThrownBy(() -> pollingService(temp, 14).mapNoticeSnapshots(temp, OBSERVED_AT))
				.isInstanceOf(java.io.IOException.class).hasMessageContaining("incomplete listing");
	}

	private static MaritimeSnapshotPollingService pollingService(Path directory, int lookbackDays) {
		return new MaritimeSnapshotPollingService(new ObjectMapper(), new MaritimeSnapshotNormalizer(),
				directory.toString(), "python3", 30, 0, 3, lookbackDays,
				MaritimeSnapshotPollingService.DEFAULT_NEA_AREAS);
	}

	private static final class MemoryStore implements MaritimeSnapshotObservationStore {
		private final List<MaritimeSnapshotObservation> rows = new ArrayList<>();
		@Override public Optional<MaritimeSnapshotObservation> head(String type, String identity) {
			return rows.stream().filter(MaritimeSnapshotObservation::isHead).findFirst();
		}
		@Override public Optional<MaritimeSnapshotObservation> find(String type, String identity, String fingerprint) {
			return rows.stream().filter(row -> row.getContentFingerprint().equals(fingerprint)).findFirst();
		}
		@Override public void add(MaritimeSnapshotObservation observation, boolean becomesHead) {
			if (becomesHead) rows.stream().filter(MaritimeSnapshotObservation::isHead).forEach(MaritimeSnapshotObservation::clearHead);
			if (becomesHead) observation.markHead();
			rows.add(observation);
		}
	}
}
