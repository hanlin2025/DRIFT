package com.drift.backend.maritimechange;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.shipment.Shipment;

/** Resolves only the newest persisted LIVE MPA revision that matches the shipment's mother-vessel visit. */
@Service
public class VerifiedMpaArrivalResolver {
	private final MaritimeSnapshotObservationStore observations;
	private final MaritimeSnapshotShipmentMatcher matcher;
	private final Clock clock;

	VerifiedMpaArrivalResolver(MaritimeSnapshotObservationStore observations,
			MaritimeSnapshotShipmentMatcher matcher, ObjectProvider<Clock> clocks) {
		this.observations = observations;
		this.matcher = matcher;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	@Transactional(readOnly = true)
	public Optional<VerifiedMpaArrival> latestFor(Shipment shipment) {
		if (shipment == null || !MaritimeImo.valid(shipment.getMotherVesselImo())) return Optional.empty();
		if (shipment.getPlannedFeederDepartureAt() == null
				|| !shipment.getPlannedFeederDepartureAt().isAfter(OffsetDateTime.now(clock))) return Optional.empty();
		MaritimeSnapshotObservation observation = observations.latestMpaHeadForImo(shipment.getMotherVesselImo()).orElse(null);
		if (observation == null) return Optional.empty();
		MaritimeSnapshotChange change = toChange(observation);
		MaritimeShipmentCandidate candidate = matcher.matchActive(change).candidates().stream()
				.filter(value -> value.shipmentId() == shipment.getId())
				.findFirst().orElse(null);
		return verifiedFor(change, candidate);
	}

	@Transactional(readOnly = true)
	Optional<VerifiedMpaArrival> verifiedFor(MaritimeSnapshotChange change, MaritimeShipmentCandidate candidate) {
		OffsetDateTime arrival = eligibleArrival(change);
		if (arrival == null) return Optional.empty();
		if (!isOperationalCandidate(candidate)) return Optional.empty();
		return Optional.of(new VerifiedMpaArrival(arrival, change.sourceIdentity(), change.sourceEventAt(),
				change.retrievedAtUtc(), change.provenance()));
	}

	private OffsetDateTime eligibleArrival(MaritimeSnapshotChange change) {
		if (change == null || !MaritimeSnapshotChangeDetector.MPA_ETA_REVISION.equals(change.sourceType())
				|| !MaritimeSnapshotChangeDetector.REVISED.equals(change.kind())
				|| !MaritimeSnapshotChangeDetector.FORWARD.equals(change.ordering())
				|| !change.forwardLooking()
				|| !MaritimeSnapshotChangeDetector.LIVE.equals(change.sourceMode())
				|| !MaritimeSnapshotChangeDetector.APPLICABLE.equals(change.freshness())
				|| !MaritimeImo.valid(change.vesselImo()) || change.effectiveFrom() == null
				|| change.sourceEventAt() == null || change.retrievedAtUtc() == null) {
			return null;
		}
		try {
			OffsetDateTime arrival = OffsetDateTime.parse(change.effectiveFrom());
			OffsetDateTime sourceTime = OffsetDateTime.parse(change.sourceEventAt());
			if (!arrival.toInstant().equals(sourceTime.toInstant()) || !arrival.isAfter(OffsetDateTime.now(clock))) return null;
			return arrival;
		} catch (DateTimeParseException ex) {
			return null;
		}
	}

	private static boolean isOperationalCandidate(MaritimeShipmentCandidate candidate) {
		return candidate != null && candidate.vesselSpecificImpact()
				&& MaritimeSnapshotShipmentMatcher.MOTHER.equals(candidate.vesselRole())
				&& MaritimeSnapshotShipmentMatcher.VERIFIED_ASSOCIATION.equals(candidate.association())
				&& MaritimeSnapshotChangeDetector.COMPATIBLE.equals(candidate.locationCompatibility())
				&& MaritimeSnapshotChangeDetector.COMPATIBLE.equals(candidate.timeCompatibility())
				&& !MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(candidate.activityCompatibility());
	}

	private static MaritimeSnapshotChange toChange(MaritimeSnapshotObservation row) {
		return new MaritimeSnapshotChange(row.getSourceType(), row.getSourceIdentity(), row.getContentFingerprint(),
				row.getChangeKind(), row.getOrdering(), row.isForwardLooking(), row.getProvenance(), row.getSourceEventAt(),
				row.getObservedAt(), row.getRetrievedAtUtc().toString(), row.getRetrievedAtUtc(), row.getSourceMode(),
				row.getFreshness(), row.getVesselImo(), row.getLocation(), text(row.getEffectiveFrom()),
				text(row.getEffectiveUntil()), row.getPreviousValue(), row.getCurrentValue(), row.getActivityCompatibility(),
				row.getLocationCompatibility(), row.getTimeCompatibility(), row.getRecordedAt());
	}

	private static String text(java.time.Instant value) { return value == null ? null : value.toString(); }
}
