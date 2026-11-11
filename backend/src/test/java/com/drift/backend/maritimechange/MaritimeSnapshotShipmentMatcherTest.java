package com.drift.backend.maritimechange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

class MaritimeSnapshotShipmentMatcherTest {

	private static final String MOTHER = "9351048";
	private static final String FEEDER = "7654329";
	private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-10-28T08:00:00Z");
	private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-10-28T20:00:00Z");
	private static final String OPEN = "2026-10-01T00:00:00Z";
	private static final String CLOSE = "2026-10-31T00:00:00Z";

	private final MaritimeSnapshotShipmentMatcher matcher = new MaritimeSnapshotShipmentMatcher(companyId -> List.of());

	@Test
	void anImoMatchWithoutAPlaceAndScheduleStaysUnverified() {
		MaritimeShipmentMatch match = match(change("MPA_ETA_REVISION", MOTHER, null, null, null, null, null, null),
				shipment(1L, "Singapore", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(match.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.vesselRole()).isEqualTo("MOTHER");
			assertThat(candidate.association()).isEqualTo("UNVERIFIED_IMO_ONLY");
			assertThat(candidate.vesselSpecificImpact()).isFalse();
			assertThat(candidate.locationCompatibility()).isEqualTo("UNKNOWN");
			assertThat(candidate.timeCompatibility()).isEqualTo("UNKNOWN");
		});
	}

	@Test
	void aFeederImoUsesTheFeederDepartureAndCanBeVerified() {
		MaritimeShipmentMatch match = match(
				change("MPA_ETA_REVISION", FEEDER, "Tuas", "2026-10-28T18:00:00Z", "2026-10-28T22:00:00Z", null, null, null),
				shipment(2L, "Tuas", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(match.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.vesselRole()).isEqualTo("FEEDER");
			assertThat(candidate.association()).isEqualTo("VERIFIED_ASSOCIATION");
			assertThat(candidate.vesselSpecificImpact()).isTrue();
			assertThat(candidate.timeCompatibility()).isEqualTo("COMPATIBLE");
		});
	}

	@Test
	void absentMpaCompatibilityIsDerivedFromTheVesselPortAndEffectiveWindow() {
		MaritimeShipmentMatch match = match(
				change("MPA_ETA_REVISION", FEEDER, "Tuas", "2026-10-28T18:00:00Z", "2026-10-28T22:00:00Z", null, null, null),
				shipment(23L, "Tuas", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(match.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.association()).isEqualTo("VERIFIED_ASSOCIATION");
			assertThat(candidate.locationCompatibility()).isEqualTo("COMPATIBLE");
			assertThat(candidate.timeCompatibility()).isEqualTo("COMPATIBLE");
		});
	}

	@Test
	void theMotherScheduleDoesNotBorrowTheFeederWindow() {
		MaritimeShipmentMatch match = match(
				change("MPA_ETA_REVISION", MOTHER, "Tuas", "2026-10-28T18:00:00Z", "2026-10-28T22:00:00Z", null, null, null),
				shipment(3L, "Tuas", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(match.candidates()).isEmpty();
		assertThat(match.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason).isEqualTo("OUT_OF_WINDOW");
	}

	@Test
	void anExactPeriodBoundaryStaysInsideAndOneSecondLaterDoesNot() {
		MaritimeShipmentMatch inside = match(
				change("MPA_OFFICIAL_NOTICE", MOTHER, "Singapore", OPEN, "2026-10-28T08:00:00Z", null, "COMPATIBLE", null),
				shipment(4L, "Singapore", MOTHER, FEEDER, ARRIVAL, DEPARTURE));
		MaritimeShipmentMatch outside = match(
				change("MPA_OFFICIAL_NOTICE", MOTHER, "Singapore", OPEN, "2026-10-28T07:59:59Z", null, "COMPATIBLE", null),
				shipment(4L, "Singapore", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(inside.candidates()).singleElement().extracting(MaritimeShipmentCandidate::association)
				.isEqualTo("VERIFIED_ASSOCIATION");
		assertThat(outside.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason).isEqualTo("OUT_OF_WINDOW");
	}

	@Test
	void anExplicitUnknownPlaceIsNotPromotedToVerified() {
		MaritimeShipmentMatch match = match(
				change("MPA_ETA_REVISION", MOTHER, "Tuas", OPEN, CLOSE, null, "UNKNOWN", null),
				shipment(5L, "Tuas", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(match.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.association()).isEqualTo("UNVERIFIED_IMO_ONLY");
			assertThat(candidate.locationCompatibility()).isEqualTo("UNKNOWN");
			assertThat(candidate.vesselSpecificImpact()).isFalse();
		});
	}

	@Test
	void aMissingPlannedArrivalDoesNotConfirmTheMotherAssociation() {
		MaritimeShipmentMatch match = match(
				change("MPA_ETA_REVISION", MOTHER, "Tuas", OPEN, CLOSE, null, "COMPATIBLE", null),
				shipment(6L, "Tuas", MOTHER, FEEDER, null, DEPARTURE));

		assertThat(match.candidates()).singleElement().extracting(MaritimeShipmentCandidate::association)
				.isEqualTo("UNVERIFIED_IMO_ONLY");
	}

	@Test
	void anIncompatibleNoticeIsExcludedEvenWhenTheImoMatches() {
		MaritimeShipmentMatch location = match(
				change("MPA_OFFICIAL_NOTICE", MOTHER, "Tuas", OPEN, CLOSE, null, "INCOMPATIBLE", null),
				shipment(7L, "Tuas", MOTHER, FEEDER, ARRIVAL, DEPARTURE));
		MaritimeShipmentMatch activity = match(
				change("MPA_OFFICIAL_NOTICE", MOTHER, "Tuas", OPEN, CLOSE, "INCOMPATIBLE", "COMPATIBLE", "COMPATIBLE"),
				shipment(7L, "Tuas", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(location.candidates()).isEmpty();
		assertThat(location.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason).isEqualTo("INCOMPATIBLE");
		assertThat(activity.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason).isEqualTo("INCOMPATIBLE");
	}

	@Test
	void aDifferentVesselAtTheSamePortIsNotACandidate() {
		MaritimeShipmentMatch match = match(
				change("MPA_ETA_REVISION", "1234567", "Tuas", OPEN, CLOSE, null, null, null),
				shipment(8L, "Tuas", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(match.candidates()).isEmpty();
		assertThat(match.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason).isEqualTo("NOT_ASSOCIATED");
	}

	@Test
	void aNoticeWithoutAVesselNeedsACompatiblePlaceAndPeriod() {
		MaritimeShipmentMatch matched = match(
				change("MPA_OFFICIAL_NOTICE", null, "Tuas Terminal Phase 2", OPEN, CLOSE, "UNKNOWN", null, null),
				shipment(9L, "Tuas", MOTHER, FEEDER, ARRIVAL, DEPARTURE));
		MaritimeShipmentMatch singapore = match(
				change("MPA_OFFICIAL_NOTICE", null, "Tuas", OPEN, CLOSE, null, "UNKNOWN", null),
				shipment(10L, "Singapore", MOTHER, FEEDER, ARRIVAL, DEPARTURE));
		MaritimeShipmentMatch rotterdam = match(
				change("MPA_OFFICIAL_NOTICE", null, "Tuas", OPEN, CLOSE, null, null, null),
				shipment(11L, "Rotterdam", MOTHER, FEEDER, ARRIVAL, DEPARTURE));
		MaritimeShipmentMatch unknown = match(
				change("MPA_OFFICIAL_NOTICE", null, null, null, null, "UNKNOWN", "UNKNOWN", "UNKNOWN"),
				shipment(12L, "Singapore", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(matched.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.association()).isEqualTo("UNCONFIRMED_CONTEXT");
			assertThat(candidate.vesselRole()).isEqualTo("NONE");
			assertThat(candidate.vesselSpecificImpact()).isFalse();
		});
		assertThat(singapore.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason).isEqualTo("NOT_ASSOCIATED");
		assertThat(rotterdam.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason).isEqualTo("INCOMPATIBLE");
		assertThat(unknown.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason).isEqualTo("NOT_ASSOCIATED");
	}

	@Test
	void aNoticeWithNoScheduleCannotBeConfirmed() {
		MaritimeShipmentMatch match = match(
				change("MPA_OFFICIAL_NOTICE", null, "Singapore", OPEN, CLOSE, null, "COMPATIBLE", null),
				shipment(13L, "Singapore", MOTHER, FEEDER, null, null));

		assertThat(match.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.association()).isEqualTo("UNCONFIRMED_CONTEXT");
			assertThat(candidate.vesselSpecificImpact()).isFalse();
			assertThat(candidate.timeCompatibility()).isEqualTo("UNKNOWN");
		});
		assertThat(match.exclusions()).isEmpty();
	}

	@Test
	void regionalWeatherStaysRegionalAndDoesNotFollowAnImo() {
		MaritimeSnapshotChange weather = change("NEA_WEATHER", MOTHER, "West Coast", null, null, null, null, null);
		MaritimeShipmentMatch singapore = match(weather, shipment(14L, "Singapore", MOTHER, FEEDER, ARRIVAL, DEPARTURE));
		MaritimeShipmentMatch rotterdam = match(weather, shipment(15L, "Rotterdam", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(singapore.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.association()).isEqualTo("REGIONAL_CONTEXT");
			assertThat(candidate.vesselRole()).isEqualTo("NONE");
			assertThat(candidate.vesselSpecificImpact()).isFalse();
		});
		assertThat(rotterdam.candidates()).isEmpty();
		assertThat(rotterdam.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason).isEqualTo("OUTSIDE_REGION");
	}

	@Test
	void regionalWeatherOutsideItsOwnPeriodIsOutOfWindow() {
		MaritimeShipmentMatch match = match(
				change("NEA_WEATHER", null, "City", "2026-10-01T00:00:00Z", "2026-10-02T00:00:00Z", null, null, null),
				shipment(16L, "Singapore", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(match.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason).isEqualTo("OUT_OF_WINDOW");
	}

	@Test
	void absentNeaCompatibilityDerivesTimeFromTheForecastWindow() {
		MaritimeShipmentMatch match = match(
				change("NEA_WEATHER", null, "Singapore", "2026-10-28T00:00:00Z", "2026-10-29T00:00:00Z", null, null, null),
				shipment(24L, "Singapore", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(match.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.association()).isEqualTo("REGIONAL_CONTEXT");
			assertThat(candidate.locationCompatibility()).isEqualTo("COMPATIBLE");
			assertThat(candidate.timeCompatibility()).isEqualTo("COMPATIBLE");
		});
	}

	@Test
	void theSameImoOnBothVesselsIsNotConfirmed() {
		MaritimeShipmentMatch match = match(
				change("MPA_ETA_REVISION", MOTHER, "Tuas", OPEN, CLOSE, null, "COMPATIBLE", "COMPATIBLE"),
				shipment(17L, "Tuas", MOTHER, MOTHER, ARRIVAL, DEPARTURE));

		assertThat(match.candidates()).isEmpty();
		assertThat(match.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason).isEqualTo("AMBIGUOUS_VESSEL");
	}

	@Test
	void aNonmatchingShipmentIsExcludedAndAMatchingOneStaysInTheBundle() {
		MaritimeShipmentMatch match = matcher.matchFacts(8L,
				change("MPA_ETA_REVISION", MOTHER, "Singapore", null, null, null, null, null),
				List.of(shipment(21L, "Rotterdam", FEEDER, "1234567", ARRIVAL, DEPARTURE),
						shipment(22L, "Singapore", MOTHER, FEEDER, ARRIVAL, DEPARTURE)));

		assertThat(match.candidates()).extracting(MaritimeShipmentCandidate::shipmentId).containsExactly(22L);
		assertThat(match.exclusions()).extracting(MaritimeShipmentExclusion::shipmentId).containsExactly(21L);
		assertThat(match.candidates()).noneMatch(MaritimeShipmentCandidate::vesselSpecificImpact);
	}

	@Test
	void theLookupIsAskedForTheViewerAndADetectorChangeStaysUnverified() {
		java.util.concurrent.atomic.AtomicLong requested = new java.util.concurrent.atomic.AtomicLong();
		ShipmentMatchFacts visible = shipment(9L, "Singapore", MOTHER, FEEDER, ARRIVAL, DEPARTURE);
		MaritimeSnapshotShipmentMatcher scoped = new MaritimeSnapshotShipmentMatcher(companyId -> {
			requested.set(companyId);
			return List.of(visible);
		});
		MaritimeSnapshotChange detected = new MaritimeSnapshotChangeDetector(new MemoryStore(),
				java.time.Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), java.time.ZoneOffset.UTC))
				.accept(List.of(new MaritimeSnapshot("MPA_ETA_REVISION", "mpa:9351048:SIN:SIN", "MPA OCEANS-X",
						"2026-10-29 02:00:00", "2026-10-29 02:00:00", "2026-10-26T15:46:03Z", "LIVE", null, MOTHER, null,
						null, null, "2026-10-28 23:00:00", "2026-10-29 02:00:00", null, null, null)))
				.changes().getFirst();

		MaritimeShipmentMatch match = scoped.match(3L, detected);

		assertThat(requested).hasValue(3L);
		assertThat(match.viewerCompanyId()).isEqualTo(3L);
		assertThat(match.contentFingerprint()).isEqualTo(detected.contentFingerprint());
		assertThat(match.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.shipmentId()).isEqualTo(9L);
			assertThat(candidate.association()).isEqualTo("UNVERIFIED_IMO_ONLY");
			assertThat(candidate.vesselSpecificImpact()).isFalse();
		});
	}

	@Test
	void aNullChangeIsRejected() {
		assertThatThrownBy(() -> matcher.match(1L, null)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aRevisedMpaEtaVerifiesTheVisitAgainstTheExactPriorScheduledArrival() {
		MaritimeSnapshotChange revised = new MaritimeSnapshotChange("MPA_ETA_REVISION", "mpa:" + MOTHER + ":call-1",
				"a".repeat(64), "REVISED", "FORWARD", true, "MPA OCEANS-X", "2026-10-28T12:00:00Z",
				"2026-10-28T11:00:00Z", "2026-10-28T11:00:00Z", Instant.parse("2026-10-28T11:00:00Z"),
				"LIVE", "APPLICABLE", MOTHER, "Tuas", "2026-10-28T12:00:00Z", null,
				"arrival=2026-10-28T08:00:00Z;departure=2026-10-28T20:00:00Z;port=Tuas",
				"arrival=2026-10-28T12:00:00Z;departure=2026-10-28T20:00:00Z;port=Tuas",
				null, null, null, Instant.parse("2026-10-28T11:00:00Z"));
		MaritimeShipmentMatch verified = match(revised,
				shipment(30L, "Tuas", MOTHER, FEEDER, ARRIVAL, DEPARTURE));
		MaritimeSnapshotChange wrongPriorSchedule = new MaritimeSnapshotChange(revised.sourceType(), revised.sourceIdentity(),
				revised.contentFingerprint(), revised.kind(), revised.ordering(), revised.forwardLooking(), revised.provenance(),
				revised.sourceEventAt(), revised.observedAt(), revised.retrievedAtUtc(), revised.retrievedAt(), revised.sourceMode(),
				revised.freshness(), revised.vesselImo(), revised.location(), revised.effectiveFrom(), revised.effectiveUntil(),
				"arrival=2026-10-28T08:00:01Z;departure=2026-10-28T20:00:00Z;port=Tuas", revised.currentValue(),
				revised.activityCompatibility(), revised.locationCompatibility(), revised.timeCompatibility(), revised.recordedAt());
		MaritimeShipmentMatch unverified = match(wrongPriorSchedule,
				shipment(30L, "Tuas", MOTHER, FEEDER, ARRIVAL, DEPARTURE));

		assertThat(verified.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.association()).isEqualTo("VERIFIED_ASSOCIATION");
			assertThat(candidate.vesselRole()).isEqualTo("MOTHER");
			assertThat(candidate.timeCompatibility()).isEqualTo("COMPATIBLE");
		});
		assertThat(unverified.candidates()).isEmpty();
		assertThat(unverified.exclusions()).singleElement().extracting(MaritimeShipmentExclusion::reason)
				.isEqualTo("OUT_OF_WINDOW");
	}

	private MaritimeShipmentMatch match(MaritimeSnapshotChange change, ShipmentMatchFacts facts) {
		return matcher.matchFacts(8L, change, List.of(facts));
	}

	private static ShipmentMatchFacts shipment(long id, String port, String motherImo, String feederImo,
			OffsetDateTime arrival, OffsetDateTime departure) {
		return new ShipmentMatchFacts(id, 4L, port, motherImo, feederImo, arrival, departure);
	}

	private static final class MemoryStore implements MaritimeSnapshotObservationStore {
		@Override
		public java.util.Optional<MaritimeSnapshotObservation> head(String sourceType, String sourceIdentity) {
			return java.util.Optional.empty();
		}

		@Override
		public java.util.Optional<MaritimeSnapshotObservation> find(String sourceType, String sourceIdentity, String contentFingerprint) {
			return java.util.Optional.empty();
		}

		@Override
		public void add(MaritimeSnapshotObservation observation, boolean becomesHead) {
		}
	}

	private static MaritimeSnapshotChange change(String sourceType, String imo, String location, String from, String until,
			String activity, String locationState, String timeState) {
		return new MaritimeSnapshotChange(sourceType, "snapshot-1", "a".repeat(64), "NEW", "FORWARD", false,
				"curated snapshot", "2026-10-29 02:00:00", "2026-10-26T00:00:00Z", "2026-10-26T00:00:00Z",
				Instant.parse("2026-10-26T00:00:00Z"), "LIVE", "APPLICABLE", imo, location, from, until, null, "value",
				activity, locationState, timeState, Instant.parse("2026-11-02T00:00:00Z"));
	}
}
