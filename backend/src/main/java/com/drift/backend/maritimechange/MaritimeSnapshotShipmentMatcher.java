package com.drift.backend.maritimechange;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Selects the shipments one evidence change can affect. The shipment list is the viewer's
 * existing visible set: the managing company or a linked importer, and only an active shipment.
 * An IMO match without a compatible place and schedule stays unverified. Regional weather stays
 * regional. An incompatible notice is left out of the candidate bundle.
 */
@Service
public class MaritimeSnapshotShipmentMatcher {

	static final String VERIFIED_ASSOCIATION = "VERIFIED_ASSOCIATION";
	static final String UNVERIFIED_IMO_ONLY = "UNVERIFIED_IMO_ONLY";
	static final String REGIONAL_CONTEXT = "REGIONAL_CONTEXT";
	static final String UNCONFIRMED_CONTEXT = "UNCONFIRMED_CONTEXT";
	static final String MOTHER = "MOTHER";
	static final String FEEDER = "FEEDER";
	static final String NONE = "NONE";
	static final String INCOMPATIBLE = "INCOMPATIBLE";
	static final String OUT_OF_WINDOW = "OUT_OF_WINDOW";
	static final String OUTSIDE_REGION = "OUTSIDE_REGION";
	static final String NOT_ASSOCIATED = "NOT_ASSOCIATED";
	static final String AMBIGUOUS_VESSEL = "AMBIGUOUS_VESSEL";
	static final String MISSING_SCHEDULE = "MISSING_SCHEDULE";

	private static final Set<String> SINGAPORE = Set.of(
			"singapore",
			"tuas",
			"tuas terminal phase 2",
			"tuas view extension",
			"tuas south staging ground",
			"temasek fairway",
			"gul basin",
			"st gul yard",
			"loyang jetty",
			"st john s island jetty",
			"kusu island",
			"western boarding ground");

	private final VisibleShipments shipments;

	@Autowired
	public MaritimeSnapshotShipmentMatcher(VisibleShipments shipments) {
		this.shipments = shipments;
	}

	@Transactional
	public MaritimeShipmentMatch match(long viewerCompanyId, MaritimeSnapshotChange change) {
		if (change == null) {
			throw new IllegalArgumentException("Change is required");
		}
		return matchFacts(viewerCompanyId, change, shipments.forCompany(viewerCompanyId));
	}

	MaritimeShipmentMatch matchFacts(long viewerCompanyId, MaritimeSnapshotChange change, List<ShipmentMatchFacts> facts) {
		if (change == null) {
			throw new IllegalArgumentException("Change is required");
		}
		if (facts == null) {
			throw new IllegalArgumentException("Shipments are required");
		}
		List<MaritimeShipmentCandidate> candidates = new ArrayList<>();
		List<MaritimeShipmentExclusion> exclusions = new ArrayList<>();
		for (ShipmentMatchFacts shipment : facts) {
			Decision decision = decide(change, shipment);
			if (decision.candidate()) {
				candidates.add(new MaritimeShipmentCandidate(shipment.shipmentId(), shipment.owningCompanyId(),
						decision.role(), decision.association(), decision.vesselSpecificImpact(), decision.activity(),
						decision.location(), decision.time()));
			} else {
				exclusions.add(new MaritimeShipmentExclusion(shipment.shipmentId(), shipment.owningCompanyId(), decision.reason()));
			}
		}
		return new MaritimeShipmentMatch(viewerCompanyId, change.sourceType(), change.sourceIdentity(),
				change.contentFingerprint(), List.copyOf(candidates), List.copyOf(exclusions));
	}

	private static Decision decide(MaritimeSnapshotChange change, ShipmentMatchFacts facts) {
		if (MaritimeSnapshotChangeDetector.NEA_WEATHER.equals(change.sourceType())) {
			return regional(change, facts);
		}
		String role = role(change, facts);
		if ("AMBIGUOUS".equals(role)) {
			return Decision.exclude(AMBIGUOUS_VESSEL);
		}
		String activity = activity(change);
		String location = location(change, facts.transshipmentPort());
		String time = schedule(change, facts, role);
		if (MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(activity)
				|| MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(location)) {
			return Decision.exclude(INCOMPATIBLE);
		}
		if (MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(time)) {
			return Decision.exclude(OUT_OF_WINDOW);
		}
		if (MOTHER.equals(role) || FEEDER.equals(role)) {
			boolean verified = MaritimeSnapshotChangeDetector.COMPATIBLE.equals(location)
					&& MaritimeSnapshotChangeDetector.COMPATIBLE.equals(time);
			return Decision.include(role, verified ? VERIFIED_ASSOCIATION : UNVERIFIED_IMO_ONLY, verified, activity, location, time);
		}
		if (change.vesselImo() != null) {
			return Decision.exclude(NOT_ASSOCIATED);
		}
		if (MaritimeSnapshotChangeDetector.COMPATIBLE.equals(location)
				&& MaritimeSnapshotChangeDetector.COMPATIBLE.equals(time)) {
			return Decision.include(NONE, UNCONFIRMED_CONTEXT, false, activity, location, time);
		}
		if (MaritimeSnapshotChangeDetector.COMPATIBLE.equals(location) && facts.plannedMotherArrivalAt() == null
				&& facts.plannedFeederDepartureAt() == null) {
			return Decision.exclude(MISSING_SCHEDULE);
		}
		return Decision.exclude(NOT_ASSOCIATED);
	}

	private static Decision regional(MaritimeSnapshotChange change, ShipmentMatchFacts facts) {
		String activity = activity(change);
		String location = regionalLocation(change, facts.transshipmentPort());
		String time = schedule(change, facts, NONE);
		if (MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(activity)
				|| MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(location)) {
			return Decision.exclude(INCOMPATIBLE);
		}
		if (!inSingapore(facts.transshipmentPort())) {
			return Decision.exclude(OUTSIDE_REGION);
		}
		if (MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(time)) {
			return Decision.exclude(OUT_OF_WINDOW);
		}
		return Decision.include(NONE, REGIONAL_CONTEXT, false, activity, location, time);
	}

	private static String role(MaritimeSnapshotChange change, ShipmentMatchFacts facts) {
		String imo = change.vesselImo();
		if (imo == null) {
			return NONE;
		}
		boolean mother = imo.equals(facts.motherVesselImo());
		boolean feeder = imo.equals(facts.feederVesselImo());
		if (mother && feeder) {
			return "AMBIGUOUS";
		}
		if (mother) {
			return MOTHER;
		}
		if (feeder) {
			return FEEDER;
		}
		return NONE;
	}

	private static String activity(MaritimeSnapshotChange change) {
		return change.activityCompatibility() == null ? MaritimeSnapshotChangeDetector.UNKNOWN : change.activityCompatibility();
	}

	private static String regionalLocation(MaritimeSnapshotChange change, String port) {
		String supplied = change.locationCompatibility();
		if (MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(supplied)
				|| MaritimeSnapshotChangeDetector.COMPATIBLE.equals(supplied)
				|| MaritimeSnapshotChangeDetector.UNKNOWN.equals(supplied)) {
			return supplied;
		}
		if (samePlace(normal(change.location()), normal(port))) {
			return MaritimeSnapshotChangeDetector.COMPATIBLE;
		}
		return MaritimeSnapshotChangeDetector.UNKNOWN;
	}

	private static String location(MaritimeSnapshotChange change, String port) {
		String supplied = change.locationCompatibility();
		if (MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(supplied)
				|| MaritimeSnapshotChangeDetector.COMPATIBLE.equals(supplied)
				|| MaritimeSnapshotChangeDetector.UNKNOWN.equals(supplied)) {
			return supplied;
		}
		String evidence = normal(change.location());
		String shipment = normal(port);
		if (evidence.isEmpty() || shipment.isEmpty()) {
			return MaritimeSnapshotChangeDetector.UNKNOWN;
		}
		if (samePlace(evidence, shipment)) {
			return MaritimeSnapshotChangeDetector.COMPATIBLE;
		}
		if (inSingapore(evidence) && inSingapore(shipment)) {
			return MaritimeSnapshotChangeDetector.UNKNOWN;
		}
		return MaritimeSnapshotChangeDetector.INCOMPATIBLE;
	}

	private static String schedule(MaritimeSnapshotChange change, ShipmentMatchFacts facts, String role) {
		String supplied = change.timeCompatibility();
		if (MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(supplied)
				|| MaritimeSnapshotChangeDetector.COMPATIBLE.equals(supplied)
				|| MaritimeSnapshotChangeDetector.UNKNOWN.equals(supplied)) {
			return supplied;
		}
		if (change.effectiveFrom() == null && change.effectiveUntil() == null) {
			return MaritimeSnapshotChangeDetector.UNKNOWN;
		}
		if (MOTHER.equals(role)) {
			return against(change, facts.plannedMotherArrivalAt());
		}
		if (FEEDER.equals(role)) {
			return against(change, facts.plannedFeederDepartureAt());
		}
		String mother = against(change, facts.plannedMotherArrivalAt());
		String feeder = against(change, facts.plannedFeederDepartureAt());
		if (MaritimeSnapshotChangeDetector.COMPATIBLE.equals(mother) || MaritimeSnapshotChangeDetector.COMPATIBLE.equals(feeder)) {
			return MaritimeSnapshotChangeDetector.COMPATIBLE;
		}
		if (facts.plannedMotherArrivalAt() == null && facts.plannedFeederDepartureAt() == null) {
			return MaritimeSnapshotChangeDetector.UNKNOWN;
		}
		boolean motherOut = MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(mother);
		boolean feederOut = MaritimeSnapshotChangeDetector.INCOMPATIBLE.equals(feeder);
		if ((facts.plannedMotherArrivalAt() == null || motherOut) && (facts.plannedFeederDepartureAt() == null || feederOut)) {
			return MaritimeSnapshotChangeDetector.INCOMPATIBLE;
		}
		return MaritimeSnapshotChangeDetector.UNKNOWN;
	}

	private static String against(MaritimeSnapshotChange change, OffsetDateTime planned) {
		if (planned == null) {
			return MaritimeSnapshotChangeDetector.UNKNOWN;
		}
		Instant at = planned.toInstant();
		Instant from = parse(change.effectiveFrom());
		Instant until = parse(change.effectiveUntil());
		if (from == null && until == null) {
			return MaritimeSnapshotChangeDetector.UNKNOWN;
		}
		if (from != null && at.isBefore(from)) {
			return MaritimeSnapshotChangeDetector.INCOMPATIBLE;
		}
		if (until != null && at.isAfter(until)) {
			return MaritimeSnapshotChangeDetector.INCOMPATIBLE;
		}
		return MaritimeSnapshotChangeDetector.COMPATIBLE;
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

	private static boolean inSingapore(String value) {
		String normal = normal(value);
		return !normal.isEmpty() && (SINGAPORE.contains(normal) || normal.contains("singapore"));
	}

	private static boolean samePlace(String left, String right) {
		if (left.equals(right)) {
			return true;
		}
		String paddedLeft = " " + left + " ";
		String paddedRight = " " + right + " ";
		return paddedLeft.contains(paddedRight) || paddedRight.contains(paddedLeft);
	}

	private static String normal(String value) {
		if (value == null) {
			return "";
		}
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim().replaceAll(" +", " ");
	}

	private record Decision(boolean candidate, String role, String association, boolean vesselSpecificImpact, String activity,
			String location, String time, String reason) {

		private static Decision include(String role, String association, boolean vesselSpecificImpact, String activity,
				String location, String time) {
			return new Decision(true, role, association, vesselSpecificImpact, activity, location, time, null);
		}

		private static Decision exclude(String reason) {
			return new Decision(false, null, null, false, null, null, null, reason);
		}
	}
}
