package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class VerifiedMpaArrivalResolverTest {
	private static final Instant NOW = Instant.parse("2026-11-10T00:00:00Z");
	private static final String IMO = "9351048";
	private static final String EXPECTED_ARRIVAL = "2026-11-11T05:00:00Z";

	private final DefaultListableBeanFactory beans = clockProvider();
	private final VerifiedMpaArrivalResolver resolver = new VerifiedMpaArrivalResolver(new EmptyStore(),
			new MaritimeSnapshotShipmentMatcher(companyId -> List.of()), beans.getBeanProvider(Clock.class));
	private final MaritimeShipmentCandidate verifiedMother = new MaritimeShipmentCandidate(51L, 7L, "MOTHER",
			"VERIFIED_ASSOCIATION", true, "UNKNOWN", "COMPATIBLE", "COMPATIBLE");

	@Test
	void onlyVerifiedForwardLiveApplicableRevisionsAreAccepted() {
		assertThat(resolver.verifiedFor(change("LIVE", "APPLICABLE", "FORWARD", "REVISED", true), verifiedMother))
				.hasValueSatisfying(evidence -> {
					assertThat(evidence.expectedArrivalAt().toInstant()).isEqualTo(Instant.parse(EXPECTED_ARRIVAL));
					assertThat(evidence.sourceIdentity()).isEqualTo("mpa:" + IMO + ":same-call");
					assertThat(evidence.provenance()).isEqualTo("MPA OCEANS-X manifest");
				});
		assertThat(resolver.verifiedFor(change("RECORDED_REPLAY", "UNKNOWN", "FORWARD", "REVISED", true), verifiedMother))
				.isEmpty();
		assertThat(resolver.verifiedFor(change("SYNTHETIC_DEMONSTRATION", "UNKNOWN", "FORWARD", "REVISED", true), verifiedMother))
				.isEmpty();
		assertThat(resolver.verifiedFor(change("HISTORICAL_BACKFILL", "HISTORICAL", "BACKFILL", "REVISED", false), verifiedMother))
				.isEmpty();
		assertThat(resolver.verifiedFor(change("LIVE", "APPLICABLE", "OUT_OF_ORDER", "REVISED", false), verifiedMother))
				.isEmpty();
		assertThat(resolver.verifiedFor(change("LIVE", "APPLICABLE", "FORWARD", "NEW", true), verifiedMother))
				.isEmpty();
	}

	@Test
	void anImoOnlyCandidateOrMismatchedSourceTimeCannotDriveRisk() {
		MaritimeShipmentCandidate imoOnly = new MaritimeShipmentCandidate(51L, 7L, "MOTHER",
				"UNVERIFIED_IMO_ONLY", false, "UNKNOWN", "UNKNOWN", "UNKNOWN");
		assertThat(resolver.verifiedFor(change("LIVE", "APPLICABLE", "FORWARD", "REVISED", true), imoOnly)).isEmpty();
		assertThat(resolver.verifiedFor(changeWithSourceTime("2026-11-11T05:00:01Z"), verifiedMother)).isEmpty();
	}

	private static MaritimeSnapshotChange change(String mode, String freshness, String ordering, String kind, boolean forward) {
		return change(mode, freshness, ordering, kind, forward, EXPECTED_ARRIVAL);
	}

	private static MaritimeSnapshotChange changeWithSourceTime(String sourceEventAt) {
		return change("LIVE", "APPLICABLE", "FORWARD", "REVISED", true, sourceEventAt);
	}

	private static MaritimeSnapshotChange change(String mode, String freshness, String ordering, String kind,
			boolean forward, String sourceEventAt) {
		return new MaritimeSnapshotChange("MPA_ETA_REVISION", "mpa:" + IMO + ":same-call", "f".repeat(64),
				kind, ordering, forward, "MPA OCEANS-X manifest", sourceEventAt, "2026-11-10T00:00:00Z",
				"2026-11-10T00:00:00Z", NOW, mode, freshness, IMO, "Singapore", EXPECTED_ARRIVAL, null,
				"arrival=2026-11-10T02:00:00Z;departure=2026-11-11T10:00:00Z;port=Singapore",
				"arrival=" + EXPECTED_ARRIVAL + ";departure=2026-11-11T10:00:00Z;port=Singapore",
				null, null, null, NOW);
	}

	private static DefaultListableBeanFactory clockProvider() {
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		beans.registerSingleton("clock", Clock.fixed(NOW, ZoneOffset.UTC));
		return beans;
	}

	private static final class EmptyStore implements MaritimeSnapshotObservationStore {
		@Override public Optional<MaritimeSnapshotObservation> head(String sourceType, String sourceIdentity) { return Optional.empty(); }
		@Override public Optional<MaritimeSnapshotObservation> find(String sourceType, String sourceIdentity, String fingerprint) { return Optional.empty(); }
		@Override public void add(MaritimeSnapshotObservation observation, boolean becomesHead) { }
	}
}
