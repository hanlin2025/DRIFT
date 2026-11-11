package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.drift.backend.shipment.ShipmentRiskAlertActivation;
import com.drift.backend.shipment.ShipmentRiskAlertActivationStore;
import com.drift.backend.shipment.ShipmentRiskAlertEvaluationProperties;
import com.drift.backend.shipment.ShipmentRiskEvaluator;

class VerifiedMpaRiskReassessmentListenerTest {
	private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
	private static final String IMO = "9351048";

	@Test
	void onlyANewVerifiedDetectorRevisionReachesTheExistingRiskEvaluatorOnce() {
		MaritimeSnapshotShipmentMatcher matcher = mock(MaritimeSnapshotShipmentMatcher.class);
		ShipmentRiskEvaluator evaluator = mock(ShipmentRiskEvaluator.class);
		VerifiedMpaArrivalResolver arrivals = mock(VerifiedMpaArrivalResolver.class);
		ShipmentRiskAlertEvaluationProperties properties = new ShipmentRiskAlertEvaluationProperties();
		properties.setEnabled(true);
		ShipmentRiskAlertActivationStore activationStore = mock(ShipmentRiskAlertActivationStore.class);
		ShipmentRiskAlertActivation activation = mock(ShipmentRiskAlertActivation.class);
		when(activationStore.lockForRiskInput()).thenReturn(activation);
		when(activation.isActive()).thenReturn(true);

		MaritimeShipmentCandidate candidate = new MaritimeShipmentCandidate(51L, 7L, "MOTHER",
				"VERIFIED_ASSOCIATION", true, "UNKNOWN", "COMPATIBLE", "COMPATIBLE");
		when(matcher.matchActive(any())).thenReturn(new MaritimeShipmentMatch(0, "MPA_ETA_REVISION",
				"mpa:" + IMO + ":call", "f".repeat(64), List.of(candidate), List.of()));
		VerifiedMpaArrival evidence = new VerifiedMpaArrival(java.time.OffsetDateTime.parse("2026-11-11T05:00:00Z"),
				"mpa:" + IMO + ":call", "2026-11-11T05:00:00Z", "2026-11-10T00:00:01Z", "MPA OCEANS-X manifest");
		when(arrivals.verifiedFor(any(), any())).thenAnswer(invocation -> {
			MaritimeSnapshotChange change = invocation.getArgument(0);
			return "REVISED".equals(change.kind()) ? Optional.of(evidence) : Optional.empty();
		});

		VerifiedMpaRiskReassessmentListener listener = new VerifiedMpaRiskReassessmentListener(matcher, evaluator,
				arrivals, properties, activationStore);
		ApplicationEventPublisher publisher = event -> {
			if (event instanceof MaritimeSnapshotChangeDetectedEvent detected) listener.reassess(detected);
		};
		MaritimeSnapshotChangeDetector detector = new MaritimeSnapshotChangeDetector(new MemoryStore(),
				Clock.fixed(NOW, ZoneOffset.UTC), publisher);
		String identity = "mpa:" + IMO + ":call";
		MaritimeSnapshot initial = snapshot(identity, "2026-11-11T02:00:00Z",
				"arrival=2026-11-11T02:00:00Z;departure=2026-11-11T10:00:00Z;port=Singapore");
		MaritimeSnapshot revision = snapshot(identity, "2026-11-11T05:00:00Z",
				"arrival=2026-11-11T05:00:00Z;departure=2026-11-11T10:00:00Z;port=Singapore");

		assertThat(detector.accept(List.of(initial)).changes()).hasSize(1);
		assertThat(detector.accept(List.of(revision)).changes()).singleElement()
				.extracting(MaritimeSnapshotChange::kind).isEqualTo("REVISED");
		assertThat(detector.accept(List.of(revision)).changes()).isEmpty();

		verify(evaluator, times(1)).evaluateMpaExpectedArrival(51L, evidence);
	}

	private static MaritimeSnapshot snapshot(String identity, String sourceTime, String currentValue) {
		return new MaritimeSnapshot("MPA_ETA_REVISION", identity, "MPA OCEANS-X manifest", sourceTime,
				"2026-11-10T00:00:00Z", "2026-11-10T00:00:00Z", "LIVE", "APPLICABLE", IMO, "Singapore",
				sourceTime, null, null, currentValue, null, null, null);
	}

	private static final class MemoryStore implements MaritimeSnapshotObservationStore {
		private final List<MaritimeSnapshotObservation> rows = new ArrayList<>();
		@Override public Optional<MaritimeSnapshotObservation> head(String type, String identity) {
			return rows.stream().filter(row -> row.isHead() && row.getSourceType().equals(type)
					&& row.getSourceIdentity().equals(identity)).findFirst();
		}
		@Override public Optional<MaritimeSnapshotObservation> find(String type, String identity, String fingerprint) {
			return rows.stream().filter(row -> row.getSourceType().equals(type)
					&& row.getSourceIdentity().equals(identity) && row.getContentFingerprint().equals(fingerprint)).findFirst();
		}
		@Override public void add(MaritimeSnapshotObservation observation, boolean becomesHead) {
			if (becomesHead) {
				rows.stream().filter(row -> row.getSourceType().equals(observation.getSourceType())
						&& row.getSourceIdentity().equals(observation.getSourceIdentity()) && row.isHead())
						.forEach(MaritimeSnapshotObservation::clearHead);
				observation.markHead();
			}
			rows.add(observation);
		}
	}
}
