package com.drift.backend.maritimechange;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The stored maritime observations that still apply, matched to shipments in one pass.
 * Only a live, applicable head row is read. Current weather is matched against the time of this read,
 * because it can explain how a vessel is moving now and says nothing about a planned time days away.
 * A notice is used for {@link #NOTICE_RECENCY} after its release. An expected arrival more than
 * {@link #ETA_PAST_LIMIT} in the past is not used.
 */
@Service
public class CurrentMaritimeSignals {

	static final Duration NOTICE_RECENCY = Duration.ofDays(14);
	static final Duration ETA_PAST_LIMIT = Duration.ofHours(24);

	private final MaritimeSnapshotObservationStore store;
	private final MaritimeSnapshotShipmentMatcher matcher;
	private final Clock clock;

	@Autowired
	public CurrentMaritimeSignals(MaritimeSnapshotObservationStore store, MaritimeSnapshotShipmentMatcher matcher) {
		this(store, matcher, Clock.systemUTC());
	}

	CurrentMaritimeSignals(MaritimeSnapshotObservationStore store, MaritimeSnapshotShipmentMatcher matcher, Clock clock) {
		this.store = store;
		this.matcher = matcher;
		this.clock = clock;
	}

	/** Matched signals by shipment id. A shipment with no signal has no entry. */
	@Transactional(readOnly = true)
	public Map<Long, List<MaritimeSignal>> forShipments(List<ShipmentMatchFacts> shipments) {
		if (shipments == null || shipments.isEmpty()) {
			return Map.of();
		}
		Instant now = clock.instant();
		List<MaritimeSnapshotChange> current = store.currentHeads(now).stream()
				.map(MaritimeSnapshotObservation::toChange)
				.filter(change -> stillApplies(change, now))
				.toList();
		if (current.isEmpty()) {
			return Map.of();
		}
		List<ShipmentMatchFacts> atNow = shipments.stream().map(facts -> atTime(facts, now)).toList();
		Map<Long, List<MaritimeSignal>> found = new HashMap<>();
		for (MaritimeSnapshotChange change : current) {
			List<ShipmentMatchFacts> facts = MaritimeSnapshotChangeDetector.NEA_WEATHER.equals(change.sourceType())
					? atNow
					: shipments;
			MaritimeShipmentMatch match = matcher.matchFacts(0L, change, facts);
			for (MaritimeShipmentCandidate candidate : match.candidates()) {
				found.computeIfAbsent(candidate.shipmentId(), id -> new ArrayList<>()).add(signal(candidate, change));
			}
		}
		found.replaceAll((id, signals) -> List.copyOf(signals));
		return Map.copyOf(found);
	}

	/** The stored head's values, so a collector can name the value an expected arrival was revised from. */
	@Transactional(readOnly = true)
	public Optional<HeadValues> head(String sourceType, String sourceIdentity) {
		return store.head(sourceType, sourceIdentity)
				.map(row -> new HeadValues(row.getPreviousValue(), row.getCurrentValue()));
	}

	public record HeadValues(String previousValue, String currentValue) {
	}

	static boolean stillApplies(MaritimeSnapshotChange change, Instant now) {
		return switch (change.sourceType()) {
			case MaritimeSnapshotChangeDetector.NEA_WEATHER -> change.effectiveUntil() != null;
			case MaritimeSnapshotChangeDetector.MPA_OFFICIAL_NOTICE -> {
				Instant released = parse(change.effectiveFrom());
				yield released != null && !released.isBefore(now.minus(NOTICE_RECENCY));
			}
			case MaritimeSnapshotChangeDetector.MPA_ETA_REVISION -> {
				Instant expected = parse(change.currentValue());
				yield expected != null && !expected.isBefore(now.minus(ETA_PAST_LIMIT));
			}
			default -> false;
		};
	}

	private static ShipmentMatchFacts atTime(ShipmentMatchFacts facts, Instant now) {
		OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
		return new ShipmentMatchFacts(facts.shipmentId(), facts.owningCompanyId(), facts.transshipmentPort(),
				facts.motherVesselImo(), facts.feederVesselImo(), at, at);
	}

	private static MaritimeSignal signal(MaritimeShipmentCandidate candidate, MaritimeSnapshotChange change) {
		return new MaritimeSignal(candidate.shipmentId(), change.sourceType(), change.sourceIdentity(),
				candidate.association(), candidate.vesselRole(), candidate.timeCompatibility(), change.location(),
				change.previousValue(), change.currentValue(), change.sourceEventAt(), change.effectiveFrom(),
				change.effectiveUntil(), change.retrievedAt(), change.provenance());
	}

	private static Instant parse(String value) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException exception) {
			return null;
		}
	}
}
