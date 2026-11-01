package com.drift.backend.maritimeai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.drift.backend.maritimecontext.MaritimeEvidence;

/** Stable references to backend-owned facts. They let an interpretation point at facts without
 * regenerating vessel names, timestamps, revisions, or measurements in model-authored text. */
final class MaritimeAiCanonicalFactReferences {
	static final String PORT_OVERLAP_REFERENCE = "relationship:PORT_CONTEXT_OVERLAPS_REVISED_EXPECTED_ARRIVAL";
	static final String BUSINESS_DEADLINE = "business:TRANSSHIPMENT_ARRIVAL_DEADLINE";
	static final String BUSINESS_DEADLINE_PORT = "business:TRANSSHIPMENT_ARRIVAL_DEADLINE_PORT";
	static final String BUSINESS_DEADLINE_APPLICABILITY = "business:TRANSSHIPMENT_ARRIVAL_DEADLINE_APPLICABILITY";
	static final String BUSINESS_DEADLINE_NOT_RECORDED = "business:TRANSSHIPMENT_ARRIVAL_DEADLINE_NOT_RECORDED";
	static final String COMPARISON_OUTCOME = "comparison:DELAY_OUTCOME";
	static final String COMPARISON_UNAVAILABLE_REASON = "comparison:UNAVAILABLE_REASON";
	static final String COMPARISON_ESTIMATED_ARRIVAL = "comparison:ESTIMATED_ARRIVAL";
	static final String COMPARISON_RECORDED_DEADLINE = "comparison:RECORDED_DEADLINE";
	static final String COMPARISON_RECORDED_PORT = "comparison:RECORDED_TRANSSHIPMENT_PORT";
	private static final Pattern SGT_EFFECTIVE_WINDOW = Pattern.compile("(?i)effective\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})-(\\d{2}:\\d{2})\\s+sgt");
	private MaritimeAiCanonicalFactReferences() { }
	static Set<String> allowed(MaritimeAiEvidencePackage input) {
		Set<String> refs = new LinkedHashSet<>();
		if (input.shipmentContext() != null) {
			if (input.shipmentContext().motherVessel() != null && input.shipmentContext().motherVessel().plannedTime() != null)
				refs.add("schedule:MOTHER:PLANNED_ARRIVAL");
			if (input.shipmentContext().feederVessel() != null && input.shipmentContext().feederVessel().plannedTime() != null)
				refs.add("schedule:FEEDER:PLANNED_DEPARTURE");
			MaritimeAiConnectionRiskContext risk = input.shipmentContext().connectionRisk();
			if (risk != null) {
				if (risk.assessed()) refs.add("risk:CONNECTION_SEVERITY");
				if (risk.plannedWindow() != null && risk.plannedWindow().state() != MaritimeAiConnectionRiskContext.WindowState.INCOMPLETE)
					refs.add("risk:PLANNED_CONNECTION_WINDOW");
				if (risk.remainingWindow() != null) refs.add("risk:REMAINING_CONNECTION_WINDOW");
				if (risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE)
					refs.add("risk:AIS_ESTIMATED_ARRIVAL");
				if (risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.PLANNED_SHIPMENT_SCHEDULE
						&& risk.arrivalEstimate() != null
						&& risk.arrivalEstimate().state() != MaritimeAiConnectionRiskContext.EstimateState.UNKNOWN)
					refs.add("risk:ARRIVAL_ESTIMATE_NOT_USED");
				if (risk.arrivalEstimate() != null
						&& risk.arrivalEstimate().state() != MaritimeAiConnectionRiskContext.EstimateState.UNKNOWN)
					refs.add("risk:ARRIVAL_ESTIMATE_FRESHNESS");
			}
			addBusinessReferences(refs, input.shipmentContext().businessContext());
		}
		input.evidence().forEach(item -> refs.add("evidence:" + item.evidenceId()));
		if (hasVerifiedPortOverlap(input)) refs.add(PORT_OVERLAP_REFERENCE);
		return Set.copyOf(refs);
	}
	static List<String> riskReferences(MaritimeAiEvidencePackage input) {
		Set<String> allowed = allowed(input);
		List<String> references = new java.util.ArrayList<>();
		if (allowed.contains("risk:CONNECTION_SEVERITY")) references.add("risk:CONNECTION_SEVERITY");
		MaritimeAiConnectionRiskContext risk = input.shipmentContext() == null
				? null : input.shipmentContext().connectionRisk();
		if (risk != null && risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE) {
			if (allowed.contains("risk:REMAINING_CONNECTION_WINDOW")) references.add("risk:REMAINING_CONNECTION_WINDOW");
			if (allowed.contains("risk:AIS_ESTIMATED_ARRIVAL")) references.add("risk:AIS_ESTIMATED_ARRIVAL");
		} else if (risk != null && risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.PLANNED_SHIPMENT_SCHEDULE
				&& allowed.contains("risk:PLANNED_CONNECTION_WINDOW")) {
			references.add("risk:PLANNED_CONNECTION_WINDOW");
		}
		if (allowed.contains("risk:ARRIVAL_ESTIMATE_NOT_USED")) references.add("risk:ARRIVAL_ESTIMATE_NOT_USED");
		if (allowed.contains("risk:ARRIVAL_ESTIMATE_FRESHNESS")) references.add("risk:ARRIVAL_ESTIMATE_FRESHNESS");
		return List.copyOf(references);
	}
	static List<String> businessReferences(MaritimeAiEvidencePackage input) {
		return allowed(input).stream().filter(reference -> reference.startsWith("business:")
				|| reference.startsWith("comparison:")).sorted().toList();
	}
	private static void addBusinessReferences(Set<String> refs, MaritimeAiBusinessContext business) {
		if (business == null) return;
		MaritimeAiBusinessContext.Deadline deadline = business.deadline();
		if (deadline != null) {
			switch (deadline.state()) {
				case RECORDED -> {
					if (deadline.instant() != null) refs.add(BUSINESS_DEADLINE);
					if (deadline.recordedTransshipmentPort() != null) refs.add(BUSINESS_DEADLINE_PORT);
					if (deadline.applicableToCurrentPort() != null) refs.add(BUSINESS_DEADLINE_APPLICABILITY);
				}
				case NOT_RECORDED -> refs.add(BUSINESS_DEADLINE_NOT_RECORDED);
				case UNKNOWN -> { }
			}
		}
		MaritimeAiBusinessContext.Comparison comparison = business.comparison();
		if (comparison == null) return;
		switch (comparison.state()) {
			case AVAILABLE -> { if (comparison.outcome() != null) refs.add(COMPARISON_OUTCOME); }
			case UNAVAILABLE -> { if (comparison.unavailableReason() != null) refs.add(COMPARISON_UNAVAILABLE_REASON); }
			case UNKNOWN -> { }
		}
		if (comparison.estimatedArrivalAt() != null) refs.add(COMPARISON_ESTIMATED_ARRIVAL);
		if (comparison.comparedDeadline() != null) refs.add(COMPARISON_RECORDED_DEADLINE);
		if (comparison.recordedTransshipmentPort() != null) refs.add(COMPARISON_RECORDED_PORT);
	}
	private static boolean hasVerifiedPortOverlap(MaritimeAiEvidencePackage input) {
		return revisedExpectedArrival(input).map(arrival -> input.evidence().stream()
				.filter(item -> "PORT_CONTEXT".equals(item.associationScope()))
				.anyMatch(item -> effectiveWindowContains(item, arrival))).orElse(false);
	}
	private static java.util.Optional<OffsetDateTime> revisedExpectedArrival(MaritimeAiEvidencePackage input) {
		return input.evidence().stream()
				.filter(item -> "MPA_ETA_REVISION".equals(item.sourceType()) && "MOTHER".equals(item.vesselRole())
						&& "EXPECTED_ARRIVAL".equals(item.observationType()) && item.evidenceSufficient()
						&& "COMPATIBLE".equals(item.compatibilityState()) && item.currentValue() != null)
				.sorted(java.util.Comparator.comparing(MaritimeEvidence::retrievedAtUtc, java.util.Comparator.nullsLast(String::compareTo)).reversed())
				.map(item -> parseOffset(item.currentValue())).flatMap(java.util.Optional::stream).findFirst();
	}
	private static boolean effectiveWindowContains(MaritimeEvidence item, OffsetDateTime arrival) {
		if (item.currentValue() == null) return false;
		Matcher window = SGT_EFFECTIVE_WINDOW.matcher(item.currentValue());
		if (!window.find()) return false;
		try {
			if (!java.time.LocalDate.parse(window.group(1)).equals(arrival.toLocalDate())) return false;
			LocalTime time = arrival.toLocalTime();
			// Effective start is inclusive; end is exclusive, avoiding an invented overlap at exactly 12:00.
			return !time.isBefore(LocalTime.parse(window.group(2))) && time.isBefore(LocalTime.parse(window.group(3)));
		} catch (RuntimeException ignored) { return false; }
	}
	private static java.util.Optional<OffsetDateTime> parseOffset(String value) {
		try { return java.util.Optional.of(OffsetDateTime.parse(value)); }
		catch (RuntimeException ignored) { return java.util.Optional.empty(); }
	}
}
