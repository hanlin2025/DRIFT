package com.drift.backend.maritimecontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.maritimechange.MaritimeSnapshotEvidenceReader;
import com.drift.backend.maritimechange.MaritimeSnapshotShipmentMatcher;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentStatus;

class PersistedMpaEtaEvidenceAdapterTest {
	private static final Instant CUTOFF = Instant.parse("2026-11-11T04:00:00Z");
	private static final String IDENTITY = "mpa:9351048:9VTEST:SEA:Singapore:2026-11-12";
	private static final String VALUE = "arrival=2026-11-12 02:00:00;departure=2026-11-12 12:00:00;port=Singapore";
	private static final String REVISED = "arrival=2026-11-12 04:00:00;departure=2026-11-12 12:00:00;port=Singapore";

	private final MaritimeSnapshotShipmentMatcher matcher = MaritimeSnapshotShipmentMatcher.forSingleShipmentAssociation();

	@Test
	void matchesTheMotherVesselPortAndEffectiveWindowAndKeepsCollectorProvenance() {
		MaritimeEvidence evidence = evidenceFor(shipment("Singapore", "9351048", null,
				"2026-11-12T02:00:00+08:00", "2026-11-12T18:00:00+08:00"),
				head(IDENTITY, "fingerprint-1", "9351048", "Singapore", VALUE, null, "2026-11-11T03:00:00Z")).getFirst();

		assertThat(evidence.sourceType()).isEqualTo("MPA_ETA_REVISION");
		assertThat(evidence.sourceMode()).isEqualTo("LIVE");
		assertThat(evidence.evidenceId()).isEqualTo("mpa-eta:" + IDENTITY + ":fingerprint-1");
		assertThat(evidence.citation()).contains("source identity: " + IDENTITY, "fingerprint: fingerprint-1",
				"retrieved: 2026-11-11T03:00:00Z", "provenance: mpa-oceansx:due_to_arrive.manifest.json");
		assertThat(evidence.currentValue()).isEqualTo(VALUE);
		assertThat(evidence.previousValue()).isNull();
		assertThat(evidence.vesselIdentity()).isEqualTo("9351048");
		assertThat(evidence.vesselRole()).isEqualTo("MOTHER");
		assertThat(evidence.freshness()).isEqualTo("APPLICABLE");
		assertThat(evidence.evidenceSufficient()).isTrue();
		assertThat(evidence.compatibilityState()).isEqualTo("COMPATIBLE");
		assertThat(evidence.limitation()).contains("arrival 2026-11-12 02:00:00", "port Singapore",
				PersistedMpaEtaEvidenceAdapter.HEAD_ONLY_CUTOFF_LIMITATION,
				PersistedMpaEtaEvidenceAdapter.DATE_IDENTITY_LIMITATION);
		assertThat(PersistedMpaEtaEvidenceAdapter.parse(VALUE)).satisfies(parsed -> {
			assertThat(parsed.arrival()).isEqualTo("2026-11-12 02:00:00");
			assertThat(parsed.departure()).isEqualTo("2026-11-12 12:00:00");
			assertThat(parsed.port()).isEqualTo("Singapore");
		});
	}

	@Test
	void excludesADifferentVesselAndQualifiesAnIncompatibleLocationOrWindow() {
		var reader = mock(MaritimeSnapshotEvidenceReader.class);
		when(reader.heads("MPA_ETA_REVISION")).thenReturn(List.of(
				head(IDENTITY, "fingerprint-1", "9351048", "PEBGA", VALUE, null, "2026-11-11T03:00:00Z"),
				head("mpa:0000000:OTHER:SEA:Singapore:2026-11-12", "fingerprint-other", "0000000", "Singapore", VALUE, null,
						"2026-11-11T03:00:00Z"),
				head("mpa:9351048:9VTEST:SEA:Singapore:2026-11-20", "fingerprint-late", "9351048", "Singapore",
						"arrival=2026-11-20 02:00:00;departure=2026-11-20 12:00:00;port=Singapore", null,
						"2026-11-11T03:00:00Z", Instant.parse("2026-11-20T00:00:00Z"), Instant.parse("2026-11-20T08:00:00Z"))));

		List<MaritimeEvidence> evidence = new PersistedMpaEtaEvidenceAdapter(reader, matcher).forShipment(
				shipment("Singapore", "9351048", null, "2026-11-12T02:00:00+08:00", "2026-11-12T18:00:00+08:00"), CUTOFF);

		assertThat(evidence).extracting(MaritimeEvidence::evidenceId)
				.doesNotContain("mpa-eta:mpa:0000000:OTHER:SEA:Singapore:2026-11-12:fingerprint-other");
		assertThat(evidence).allSatisfy(item -> {
			assertThat(item.compatibilityState()).isEqualTo("INCOMPATIBLE");
			assertThat(item.evidenceSufficient()).isFalse();
			assertThat(item.sourceMode()).isEqualTo("LIVE");
		});
		assertThat(evidence).extracting(MaritimeEvidence::evidenceId).contains(
				"mpa-eta:" + IDENTITY + ":fingerprint-1",
				"mpa-eta:mpa:9351048:9VTEST:SEA:Singapore:2026-11-20:fingerprint-late");
	}

	@Test
	void omitsAHeadRetrievedAfterTheCutoffInsteadOfReconstructingAnEarlierVersion() {
		var reader = mock(MaritimeSnapshotEvidenceReader.class);
		when(reader.heads("MPA_ETA_REVISION")).thenReturn(List.of(
				head(IDENTITY, "fingerprint-new", "9351048", "Singapore", REVISED, VALUE, "2026-11-11T05:00:00Z")));

		assertThat(new PersistedMpaEtaEvidenceAdapter(reader, matcher).forShipment(
				shipment("Singapore", "9351048", null, "2026-11-12T02:00:00+08:00", "2026-11-12T18:00:00+08:00"), CUTOFF))
				.isEmpty();
	}

	@Test
	void keepsOneStableIdentityForUnchangedContentAndADistinctIdentityForARevision() {
		MaritimeSnapshotEvidenceReader.Snapshot revised = head(IDENTITY, "fingerprint-2", "9351048", "Singapore", REVISED, VALUE,
				"2026-11-11T03:30:00Z");
		List<MaritimeEvidence> once = evidenceFor(shipment("Singapore", "9351048", null,
				"2026-11-12T02:00:00+08:00", "2026-11-12T18:00:00+08:00"), revised, revised);
		MaritimeSnapshotEvidenceReader.Snapshot nextDay = head("mpa:9351048:9VTEST:SEA:Singapore:2026-11-13", "fingerprint-3",
				"9351048", "Singapore", "arrival=2026-11-13 02:00:00;departure=2026-11-13 12:00:00;port=Singapore", null,
				"2026-11-11T03:30:00Z", Instant.parse("2026-11-12T18:00:00Z"), Instant.parse("2026-11-13T04:00:00Z"));
		List<MaritimeEvidence> separateCall = evidenceFor(shipment("Singapore", "9351048", null,
				"2026-11-12T20:00:00+08:00", "2026-11-13T08:00:00+08:00"), nextDay);

		assertThat(once).singleElement().satisfies(item -> {
			assertThat(item.evidenceId()).isEqualTo("mpa-eta:" + IDENTITY + ":fingerprint-2");
			assertThat(item.previousValue()).isEqualTo(VALUE);
			assertThat(item.currentValue()).isEqualTo(REVISED);
		});
		assertThat(separateCall).singleElement().satisfies(item -> {
			assertThat(item.evidenceId()).isNotEqualTo(once.getFirst().evidenceId());
			assertThat(item.sourceMode()).isEqualTo("LIVE");
			assertThat(item.previousValue()).isNull();
		});
	}

	@Test
	void keepsRecordedReplayModeAndHistoricalFreshnessExplicit() {
		var reader = mock(MaritimeSnapshotEvidenceReader.class);
		when(reader.heads("MPA_ETA_REVISION")).thenReturn(List.of(new MaritimeSnapshotEvidenceReader.Snapshot(
				"MPA_ETA_REVISION", IDENTITY, "fingerprint-replay", "recorded-replay", "2026-11-12T02:00:00+08:00",
				"2026-11-11T03:00:00Z", Instant.parse("2026-11-11T03:00:00Z"), "RECORDED_REPLAY", "HISTORICAL", "Singapore",
				Instant.parse("2026-11-11T16:00:00Z"), Instant.parse("2026-11-12T04:00:00Z"), null, VALUE, null, null, null,
				"9351048")));

		List<MaritimeEvidence> evidence = new PersistedMpaEtaEvidenceAdapter(reader, matcher).forShipment(
				shipment("Singapore", "9351048", null, "2026-11-12T02:00:00+08:00", "2026-11-12T18:00:00+08:00"), CUTOFF);

		assertThat(evidence).singleElement().satisfies(item -> {
			assertThat(item.sourceMode()).isEqualTo("RECORDED_REPLAY");
			assertThat(item.freshness()).isEqualTo("HISTORICAL");
			assertThat(item.evidenceSufficient()).isFalse();
		});
	}

	private List<MaritimeEvidence> evidenceFor(ShipmentDetailResponse shipment, MaritimeSnapshotEvidenceReader.Snapshot... heads) {
		var reader = mock(MaritimeSnapshotEvidenceReader.class);
		when(reader.heads("MPA_ETA_REVISION")).thenReturn(List.of(heads));
		return new PersistedMpaEtaEvidenceAdapter(reader, matcher).forShipment(shipment, CUTOFF);
	}

	private static MaritimeSnapshotEvidenceReader.Snapshot head(String identity, String fingerprint, String imo, String port,
			String current, String previous, String retrievedAt) {
		return head(identity, fingerprint, imo, port, current, previous, retrievedAt,
				Instant.parse("2026-11-11T16:00:00Z"), Instant.parse("2026-11-12T08:00:00Z"));
	}

	private static MaritimeSnapshotEvidenceReader.Snapshot head(String identity, String fingerprint, String imo, String port,
			String current, String previous, String retrievedAt, Instant from, Instant until) {
		return new MaritimeSnapshotEvidenceReader.Snapshot("MPA_ETA_REVISION", identity, fingerprint,
				"mpa-oceansx:due_to_arrive.manifest.json", "2026-11-12T02:00:00+08:00", retrievedAt,
				Instant.parse(retrievedAt), "LIVE", "APPLICABLE", port, from, until, previous, current, null, null, null, imo);
	}

	private static ShipmentDetailResponse shipment(String port, String motherImo, String feederImo, String arrival, String departure) {
		return new ShipmentDetailResponse(1L, 1L, "REF", "Shanghai", "Jakarta", port, "Demo mother",
				OffsetDateTime.parse(arrival), motherImo, null, null, "Demo feeder", OffsetDateTime.parse(departure), feederImo,
				null, null, Instant.EPOCH, 0L, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, ShipmentStatus.ACTIVE, null, List.of());
	}
}
