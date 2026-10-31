package com.drift.backend.maritimeai;

import java.util.LinkedHashSet;
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
	private static final Pattern SGT_EFFECTIVE_WINDOW = Pattern.compile("(?i)effective\\s+(\\d{4}-\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2})-(\\d{2}:\\d{2})\\s+sgt");
	private MaritimeAiCanonicalFactReferences() { }
	static Set<String> allowed(MaritimeAiEvidencePackage input) {
		Set<String> refs = new LinkedHashSet<>();
		if (input.shipmentContext() != null) {
			if (input.shipmentContext().motherVessel() != null && input.shipmentContext().motherVessel().plannedTime() != null)
				refs.add("schedule:MOTHER:PLANNED_ARRIVAL");
			if (input.shipmentContext().feederVessel() != null && input.shipmentContext().feederVessel().plannedTime() != null)
				refs.add("schedule:FEEDER:PLANNED_DEPARTURE");
		}
		input.evidence().forEach(item -> refs.add("evidence:" + item.evidenceId()));
		if (hasVerifiedPortOverlap(input)) refs.add(PORT_OVERLAP_REFERENCE);
		return Set.copyOf(refs);
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
