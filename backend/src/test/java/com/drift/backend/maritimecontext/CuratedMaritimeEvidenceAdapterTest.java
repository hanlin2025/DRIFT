package com.drift.backend.maritimecontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.time.Clock;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentStatus;

import tools.jackson.databind.ObjectMapper;

class CuratedMaritimeEvidenceAdapterTest {
	@Test void assemblesAuthenticMpaAndNeaHistoricalReplayOnlyAfterBothWereRetrieved() {
		var adapter = new CuratedMaritimeEvidenceAdapter(true, new ObjectMapper());
		var evidence = adapter.forShipment(shipment("9351048"), Instant.parse("2026-10-27T16:00:00Z"));
		assertThat(evidence).extracting(MaritimeEvidence::sourceType).containsExactlyInAnyOrder("MPA_ETA_REVISION", "NEA_WEATHER");
		assertThat(evidence).allSatisfy(item -> {
			assertThat(item.sourceMode()).isEqualTo("RETROSPECTIVE_ANALYSIS");
			assertThat(item.shipmentAssociationConfidence()).isEqualTo("UNVERIFIED_IMO_ONLY");
			assertThat(item.timeCompatibility()).isEqualTo("UNKNOWN");
			assertThat(item.humanReviewRequired()).isTrue();
		});
	}

	@Test void excludesFutureAvailabilityAndWrongVessel() {
		var adapter = new CuratedMaritimeEvidenceAdapter(true, new ObjectMapper());
		assertThat(adapter.forShipment(shipment("9351048"), Instant.parse("2026-10-26T15:40:00Z"))).isEmpty();
		assertThat(adapter.forShipment(shipment("0000000"), Instant.parse("2026-10-28T00:00:00Z"))).isEmpty();
		assertThat(new CuratedMaritimeEvidenceAdapter(false, new ObjectMapper()).forShipment(shipment("9351048"), Instant.now())).isEmpty();
	}

	@Test void keepsHistoricalBackfillOutOfPointInTimeEvidenceAndDoesNotAddUnavailablePlaceholder() {
		var adapter = new CuratedMaritimeEvidenceAdapter(true, new ObjectMapper());
		var evidence = adapter.forShipment(shipment("9351048"), Instant.parse("2026-10-27T16:00:00Z"));
		assertThat(evidence).allSatisfy(item -> {
			assertThat(item.sourceMode()).isEqualTo("RETROSPECTIVE_ANALYSIS");
			assertThat(item.freshness()).isEqualTo("HISTORICAL_REPLAY");
			assertThat(Instant.parse(item.retrievedAtUtc())).isBeforeOrEqualTo(Instant.parse("2026-10-27T16:00:00Z"));
		});
		assertThat(new UnavailableNeaWeatherEvidenceAdapter(true)
				.forShipment(shipment("9351048"), Instant.parse("2026-10-27T16:00:00Z"))).isEmpty();
		assertThat(new RecordedMpaRevisionEvidenceAdapter(true)
				.forShipment(shipment("9351048"), Instant.parse("2026-10-27T16:00:00Z"))).isEmpty();
	}

	@Test void excludesFutureAndNonRetrospectiveBackfillButKeepsItsProvenanceOutOfLiveMode() {
		var historicalBackfill = snapshot("NEA_WEATHER", "RETROSPECTIVE_ANALYSIS", "HISTORICAL_BACKFILL",
				"2026-10-27T15:00:00Z");
		var invalidBackfill = snapshot("NEA_WEATHER", "LIVE_SNAPSHOT", "HISTORICAL_BACKFILL",
				"2026-10-27T15:00:00Z");
		var future = snapshot("NEA_WEATHER", "RETROSPECTIVE_ANALYSIS", "LIVE_SNAPSHOT",
				"2026-10-27T17:00:00Z");
		var adapter = new CuratedMaritimeEvidenceAdapter(true, List.of(historicalBackfill, invalidBackfill, future));
		var evidence = adapter.forShipment(shipment("9351048"), Instant.parse("2026-10-27T16:00:00Z"));
		assertThat(evidence).singleElement().satisfies(item -> {
			assertThat(item.sourceMode()).isEqualTo("RETROSPECTIVE_ANALYSIS");
			assertThat(item.freshness()).isEqualTo("HISTORICAL_REPLAY");
			assertThat(item.retrievedAtUtc()).isEqualTo("2026-10-27T15:00:00Z");
		});
	}

	@Test void preservesExistingNoticeAdapterInCombinedTwoSourceBundle() {
		var service = new MaritimeContextService(true, List.of(
				new CuratedMaritimeEvidenceAdapter(true, new ObjectMapper()), new SyntheticNoticeEvidenceAdapter()),
				Clock.fixed(Instant.parse("2026-10-27T16:00:00Z"), ZoneOffset.UTC));
		var bundle = service.forShipment(shipment("9351048"));
		assertThat(bundle.sourceMode()).isEqualTo("RETROSPECTIVE_ANALYSIS");
		assertThat(bundle.evidence()).extracting(MaritimeEvidence::sourceType)
			.containsExactlyInAnyOrder("MPA_ETA_REVISION", "NEA_WEATHER", "MPA_OFFICIAL_NOTICE");
		assertThat(bundle.notices()).singleElement().extracting(MaritimeNoticeEvidence::noticeId).isEqualTo("DEMO-MPA-245-001");
	}

	private static ShipmentDetailResponse shipment(String imo) { return new ShipmentDetailResponse(1L, 1L, "REF", "SG", "ID", "SG", "KOTA LARIS", null, imo, null, null, null, null, null, null, null, Instant.EPOCH, 0L, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, ShipmentStatus.ACTIVE); }

	private static CuratedMaritimeEvidenceAdapter.Snapshot snapshot(String sourceType, String sourceMode,
			String collectionLabel, String retrievedAtUtc) {
		return new CuratedMaritimeEvidenceAdapter.Snapshot(sourceType, sourceMode, "test-" + sourceMode + collectionLabel,
				"9351048", "Test weather context", "NEA", "test-manifest", "2026-10-27T23:00:00+08:00",
				"2026-10-27T23:00:00+08:00", retrievedAtUtc, "HISTORICAL_REPLAY", "", "test", "",
				"SINGAPORE_PORT_CONTEXT_ONLY", collectionLabel, "Test only; no vessel-specific claim.");
	}
}
