package com.drift.backend.maritimecontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.maritimechange.MaritimeSnapshotEvidenceReader;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentStatus;

class PersistedNeaWeatherEvidenceAdapterTest {
	private static final Instant CUTOFF = Instant.parse("2026-11-11T01:00:00Z");

	@Test
	void exposesCurrentPersistedForecastAsRegionalEvidenceWithSourceAndRetrievalTimes() {
		var reader = mock(MaritimeSnapshotEvidenceReader.class);
		when(reader.heads("NEA_WEATHER")).thenReturn(List.of(snapshot("LIVE", "APPLICABLE",
				"2026-11-11T00:00:00Z", Instant.parse("2026-11-11T02:00:00Z"), "Tuas")));

		var evidence = new PersistedNeaWeatherEvidenceAdapter(reader).forShipment(shipment("Singapore"), CUTOFF);

		assertThat(evidence).singleElement().satisfies(item -> {
			assertThat(item.sourceType()).isEqualTo("NEA_WEATHER");
			assertThat(item.sourceMode()).isEqualTo("LIVE");
			assertThat(item.sourceEventAt()).isEqualTo("2026-11-11T00:00:00Z");
			assertThat(item.observedAt()).isEqualTo("2026-11-11T00:00:00Z");
			assertThat(item.retrievedAtUtc()).isEqualTo("2026-11-11T00:30:00Z");
			assertThat(item.title()).contains("Tuas");
			assertThat(item.citation()).contains("regional area: Tuas");
			assertThat(item.freshness()).isEqualTo("APPLICABLE");
			assertThat(item.shipmentAssociationConfidence()).isEqualTo("REGIONAL_CONTEXT");
			assertThat(item.associationScope()).isEqualTo("REGIONAL_CONTEXT");
			assertThat(item.currentValue()).isEqualTo("Cloudy");
			assertThat(item.humanReviewRequired()).isTrue();
			assertThat(item.limitation()).contains("does not establish weather at a vessel");
		});
	}

	@Test
	void labelsExpiredForecastHistoricalAndDoesNotExposeSyntheticOrFutureEvidence() {
		var reader = mock(MaritimeSnapshotEvidenceReader.class);
		when(reader.heads("NEA_WEATHER")).thenReturn(List.of(
			snapshot("LIVE", "APPLICABLE", "2026-11-10T10:00:00Z", Instant.parse("2026-11-10T12:00:00Z"), "Tuas"),
			snapshot("SYNTHETIC_DEMONSTRATION", "APPLICABLE", "2026-11-11T00:00:00Z", Instant.parse("2026-11-11T02:00:00Z"), "Pioneer"),
			snapshot("LIVE", "APPLICABLE", "2026-11-11T00:00:00Z", Instant.parse("2026-11-11T02:00:00Z"), "Tuas", "2026-11-11T01:30:00Z")));

		var evidence = new PersistedNeaWeatherEvidenceAdapter(reader).forShipment(shipment("Singapore"), CUTOFF);

		assertThat(evidence).singleElement().satisfies(item -> {
			assertThat(item.sourceMode()).isEqualTo("LIVE");
			assertThat(item.freshness()).isEqualTo("HISTORICAL");
			assertThat(item.currentValue()).isEqualTo("Cloudy");
		});
	}

	@Test
	void doesNotAssociateRegionalForecastsWithNonSingaporeShipments() {
		var reader = mock(MaritimeSnapshotEvidenceReader.class);
		when(reader.heads("NEA_WEATHER")).thenReturn(List.of(
			snapshot("LIVE", "APPLICABLE", "2026-11-11T00:00:00Z", Instant.parse("2026-11-11T02:00:00Z"), "Tuas")));

		assertThat(new PersistedNeaWeatherEvidenceAdapter(reader).forShipment(shipment("Rotterdam"), CUTOFF)).isEmpty();
	}

	@Test
	void limitsForecastsToTheSameConfiguredAreasUsedByTheCollector() {
		var reader = mock(MaritimeSnapshotEvidenceReader.class);
		when(reader.heads("NEA_WEATHER")).thenReturn(List.of(
			snapshot("LIVE", "APPLICABLE", "2026-11-11T00:00:00Z", Instant.parse("2026-11-11T02:00:00Z"), "Tuas"),
			snapshot("LIVE", "APPLICABLE", "2026-11-11T00:00:00Z", Instant.parse("2026-11-11T02:00:00Z"), "Jurong Island")));

		var evidence = PersistedNeaWeatherEvidenceAdapter.forConfiguredAreas(reader, " Tuas , Pioneer ")
				.forShipment(shipment("Singapore"), CUTOFF);

		assertThat(evidence).singleElement().satisfies(item -> assertThat(item.title()).contains("Tuas"));
	}

	@Test
	void keepsHistoricalBackfillModeAndFreshnessExplicit() {
		var reader = mock(MaritimeSnapshotEvidenceReader.class);
		when(reader.heads("NEA_WEATHER")).thenReturn(List.of(snapshot("HISTORICAL_BACKFILL", "HISTORICAL",
				"2026-11-10T00:00:00Z", Instant.parse("2026-11-10T02:00:00Z"), "Tuas")));

		var evidence = new PersistedNeaWeatherEvidenceAdapter(reader).forShipment(shipment("Singapore"), CUTOFF);

		assertThat(evidence).singleElement().satisfies(item -> {
			assertThat(item.sourceMode()).isEqualTo("HISTORICAL_BACKFILL");
			assertThat(item.freshness()).isEqualTo("HISTORICAL");
			assertThat(item.evidenceSufficient()).isFalse();
		});
	}

	@Test
	void validPersistedObservationSuppressesContradictoryUnavailablePlaceholder() {
		var reader = mock(MaritimeSnapshotEvidenceReader.class);
		when(reader.heads("NEA_WEATHER")).thenReturn(List.of(
			snapshot("LIVE", "APPLICABLE", "2026-11-11T00:00:00Z", Instant.parse("2026-11-11T02:00:00Z"), "Tuas")));
		var persisted = new PersistedNeaWeatherEvidenceAdapter(reader);

		assertThat(new UnavailableNeaWeatherEvidenceAdapter(false, false, persisted)
				.forShipment(shipment("Singapore"), CUTOFF)).isEmpty();
	}

	private static MaritimeSnapshotEvidenceReader.Snapshot snapshot(String mode, String freshness,
			String sourceAt, Instant validUntil, String area) {
		return snapshot(mode, freshness, sourceAt, validUntil, area, null);
	}

	private static MaritimeSnapshotEvidenceReader.Snapshot snapshot(String mode, String freshness,
			String sourceAt, Instant validUntil, String area, String retrievedAt) {
		return new MaritimeSnapshotEvidenceReader.Snapshot("NEA_WEATHER", "nea:two_hr_forecast:" + area,
				"fingerprint-" + area + sourceAt, "nea:two_hr_forecast:" + area + ":forecast.json", sourceAt,
				sourceAt, Instant.parse(retrievedAt == null ? "2026-11-11T00:30:00Z" : retrievedAt), mode, freshness,
				area, Instant.parse("2026-11-11T00:00:00Z"), validUntil, null, "Cloudy", null, null, null, null);
	}

	private static ShipmentDetailResponse shipment(String port) {
		return new ShipmentDetailResponse(1L, 1L, "REF", "SG", "ID", port, "KOTA LARIS",
				OffsetDateTime.parse("2026-11-12T02:00:00+08:00"), "9351048", null, null, null, null, null, null, null,
				Instant.EPOCH, 0L, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, ShipmentStatus.ACTIVE, null, List.of());
	}
}
