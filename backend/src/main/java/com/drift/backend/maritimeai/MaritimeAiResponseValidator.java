package com.drift.backend.maritimeai;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.drift.backend.maritimecontext.MaritimeEvidence;

/**
 * Rejects bounded, deterministic contradictions between structured evidence facts and an AI
 * interpretation. Citation membership alone cannot prove natural-language entailment, so human
 * review remains required for all accepted output.
 */
final class MaritimeAiResponseValidator {
	private static final Pattern FEEDER_ARRIVAL_OR_REVISION = Pattern.compile("\\bfeeder(?:\\s+vessel)?\\b[^.!?;]{0,120}\\b(arriv\\w*|eta|revis\\w*)\\b");
	private static final Pattern MOTHER_ARRIVAL_OR_REVISION = Pattern.compile("\\bmother(?:\\s+vessel)?\\b[^.!?;]{0,120}\\b(arriv\\w*|eta|revis\\w*)\\b");
	private static final Pattern FEEDER_DEPARTURE = Pattern.compile("\\bfeeder(?:\\s+vessel)?\\b[^.!?;]{0,120}\\bdeparture\\b");
	private static final Pattern PLANNED_FEEDER_DEPARTURE = Pattern.compile("\\b(planned|scheduled)\\s+feeder(?:\\s+vessel)?\\b[^.!?;]{0,80}\\bdeparture\\b|\\bfeeder(?:\\s+vessel)?\\b[^.!?;]{0,80}\\b(planned|scheduled)\\b[^.!?;]{0,80}\\bdeparture\\b|\\bfeeder(?:\\s+vessel)?\\b[^.!?;]{0,80}\\bdeparture\\b[^.!?;]{0,80}\\b(planned|scheduled)\\b");
	private static final Pattern OBSERVED_FEEDER_DEPARTURE = Pattern.compile("\\bfeeder(?:\\s+vessel)?\\b[^.!?;]{0,120}\\b(departed|observed|actual)\\b");
	/* A separately scoped feeder mention ends the mother-vessel scope.  This is a narrow
	 * contradiction guard, not a parser: a planned feeder departure is legitimate shipment
	 * context alongside an observed mother expected arrival. */
	private static final Pattern MOTHER_DEPARTURE = Pattern.compile("\\bmother(?:\\s+vessel)?\\b(?:(?!\\bfeeder(?:\\s+vessel)?\\b)[^.!?;]){0,120}\\bdeparture\\b");
	private static final Pattern FACTUAL_NUMBER = Pattern.compile("\\d");
	private static final Pattern FACTUAL_DURATION = Pattern.compile("(?i)\\b(?:an?|one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s+(?:minute|minutes|hour|hours|day|days)\\b");
	private static final Pattern REVISION_DURATION = Pattern.compile("(?i)\\b(?:revis(?:ed|ion)|change(?:d)?|delay(?:ed)?)\\b[^.!?;]{0,48}?\\b(an?|one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s+(minute|minutes|hour|hours|day|days)\\b");
	private static final Pattern UNSUPPORTED_REVISION_QUALIFIER = Pattern.compile("(?i)\\b(?:additional|further|another|subsequent)\\b");
	private static final Pattern REVISION_DELAY_DIRECTION = Pattern.compile("(?i)\\bdelay(?:ed)?\\b");
	private static final Pattern REVISION_EARLIER_DIRECTION = Pattern.compile("(?i)\\b(?:earlier|advance(?:d)?)\\b");
	private static final Map<String, Long> WRITTEN_NUMBERS = Map.ofEntries(
			Map.entry("a", 1L), Map.entry("an", 1L), Map.entry("one", 1L), Map.entry("two", 2L), Map.entry("three", 3L),
			Map.entry("four", 4L), Map.entry("five", 5L), Map.entry("six", 6L), Map.entry("seven", 7L), Map.entry("eight", 8L),
			Map.entry("nine", 9L), Map.entry("ten", 10L));
	private MaritimeAiResponseValidator() { }

	static String validate(MaritimeAiInterpretation output, MaritimeAiEvidencePackage input) {
		if (output != null && output.formalRecommendationCode() != null) return "FORMAL_RECOMMENDATION_NOT_OWNED_BY_CDG247";
		if (output == null || output.summary() == null || output.summary().isBlank() || output.claims() == null
				|| output.uncertainties() == null || output.followUpConsiderations() == null
				|| (output.evidenceSufficient() && output.claims().isEmpty())) return "MALFORMED_OUTPUT";
		if (!"VERIFIED".equals(input.shipmentAssociationConfidence()) && referencesShipmentAsFact(output.summary(), "")) {
			return "UNVERIFIED_SHIPMENT_ASSOCIATION";
		}
		if (claimsCargoAsFact(output.summary())) return "UNSUPPORTED_CARGO_ASSERTION";
		if (claimsMissedConnection(output.summary())) return "UNSUPPORTED_MISSED_CONNECTION";
		if (claimsCausation(output.summary())) return "UNSUPPORTED_CAUSAL_CLAIM";
		Set<String> ids = input.evidence().stream().map(MaritimeEvidence::evidenceId).collect(Collectors.toSet());
		Set<String> allowedFactReferences = MaritimeAiCanonicalFactReferences.allowed(input);
		for (MaritimeAiClaim claim : output.claims()) {
			if (claim == null || claim.statement() == null || claim.statement().isBlank() || claim.evidenceIds() == null || claim.evidenceIds().isEmpty()) return "MISSING_CITATION";
			if (!ids.containsAll(claim.evidenceIds())) return "UNKNOWN_EVIDENCE_ID";
			List<MaritimeEvidence> cited = input.evidence().stream().filter(item -> claim.evidenceIds().contains(item.evidenceId())).toList();
			if (claim.factReferences() != null && !claim.factReferences().isEmpty()) {
				if (!allowedFactReferences.containsAll(claim.factReferences())) return "UNKNOWN_FACT_REFERENCE";
				if (containsGeneratedFactualDetail(output.summary(), input, null, List.of())) return "UNSTRUCTURED_FACTUAL_DETAIL";
				String generatedDetail = generatedDetailViolation(claim.statement(), input, claim, cited);
				if (generatedDetail != null) return generatedDetail;
			}
			if (claimsCausation(claim.statement())) return "UNSUPPORTED_CAUSAL_CLAIM";
			if (claimsMissedConnection(claim.statement())) return "UNSUPPORTED_MISSED_CONNECTION";
			if (claimsCargoAsFact(claim.statement())) return "UNSUPPORTED_CARGO_ASSERTION";
			if (!"VERIFIED".equals(input.shipmentAssociationConfidence())
					&& referencesShipmentAsFact(claim.statement(), claim.applicability())) return "UNVERIFIED_SHIPMENT_ASSOCIATION";

			for (MaritimeEvidence source : cited) {
				if ("INCOMPATIBLE".equals(source.compatibilityState()) || "UNAVAILABLE".equals(source.sourceMode())
						|| "STALE".equals(source.freshness()) || !source.evidenceSufficient()) return "INELIGIBLE_CITED_EVIDENCE";
			}
			String structuredGrounding = validateStructuredGrounding(claim, cited, input);
			if (structuredGrounding != null) return structuredGrounding;
			String relationshipGrounding = validateRelationship(claim, cited);
			if (relationshipGrounding != null) return relationshipGrounding;
		}
		return null;
	}

	private static String validateStructuredGrounding(MaritimeAiClaim claim, List<MaritimeEvidence> cited,
			MaritimeAiEvidencePackage input) {
		boolean scheduleReview = "SCHEDULE_REVIEW".equals(claim.relationshipType());
		String statement = claim.statement().toLowerCase();
		String role = singleKnown(cited.stream().map(MaritimeEvidence::vesselRole).toList());
		if (role != null && !role.equals(claim.vesselRole())) return "VESSEL_ROLE_MISMATCH";
		if (role == null && hasKnown(cited.stream().map(MaritimeEvidence::vesselRole).toList()) && claim.vesselRole() != null) return "AMBIGUOUS_VESSEL_ROLE";
		if (role == null && claim.vesselRole() != null) return "UNSUPPORTED_VESSEL_ROLE";
		if ("MOTHER".equals(role) && mentionsFeederArrivalOrRevision(statement)) return "VESSEL_ROLE_MISMATCH";
		if ("FEEDER".equals(role) && mentionsMotherArrivalOrRevision(statement)) return "VESSEL_ROLE_MISMATCH";
		if (mentionsFeederDeparture(statement) && !"FEEDER".equals(role)
				&& (!scheduleReview || !mentionsPlannedFeederDeparture(statement) || !hasPlannedFeederDeparture(input.shipmentContext()))) {
			return "UNSUPPORTED_FEEDER_SCHEDULE";
		}

		String observation = singleKnown(cited.stream().map(MaritimeEvidence::observationType).toList());
		if (observation != null && !observation.equals(claim.observationType())) return "OBSERVATION_TYPE_MISMATCH";
		if (observation == null && hasKnown(cited.stream().map(MaritimeEvidence::observationType).toList()) && claim.observationType() != null) return "AMBIGUOUS_OBSERVATION_TYPE";
		if (observation == null && claim.observationType() != null) return "UNSUPPORTED_OBSERVATION_TYPE";
		if ("EXPECTED_ARRIVAL".equals(observation) && statement.contains("departure")
				&& (!scheduleReview || mentionsMotherDeparture(statement) || !mentionsPlannedFeederDeparture(statement)
						|| !hasPlannedFeederDeparture(input.shipmentContext()))) return "OBSERVATION_TYPE_MISMATCH";
		if ("EXPECTED_DEPARTURE".equals(observation) && statement.contains("arrival")) return "OBSERVATION_TYPE_MISMATCH";

		String scope = singleKnown(cited.stream().map(MaritimeEvidence::associationScope).toList());
		if (scope != null && !scope.equals(claim.associationScope())) return "ASSOCIATION_SCOPE_MISMATCH";
		if (scope == null && hasKnown(cited.stream().map(MaritimeEvidence::associationScope).toList()) && claim.associationScope() != null) return "AMBIGUOUS_ASSOCIATION_SCOPE";
		if (scope == null && claim.associationScope() != null) return "UNSUPPORTED_ASSOCIATION_SCOPE";
		if (hasContextOnlySource(cited) && ("SHIPMENT_SPECIFIC".equals(claim.applicability())
				|| "VERIFIED".equalsIgnoreCase(claim.applicability())
				|| referencesShipmentAsFact(claim.statement(), claim.applicability()))) {
			return "CONTEXTUAL_EVIDENCE_OVERSTATED";
		}
		if (hasSyntheticNotice(cited) && callsSyntheticNoticeOfficially(claim.statement().toLowerCase())) return "SYNTHETIC_NOTICE_MISREPRESENTED";
		return null;
	}

	/** v5 validates the model's asserted relationship from typed references, rather than trying to
	 * infer which noun a free-form sentence belongs to. Null remains readable for legacy records. */
	private static String validateRelationship(MaritimeAiClaim claim, List<MaritimeEvidence> cited) {
		if (claim.relationshipType() == null || claim.relationshipType().isBlank()) return null;
		Set<String> refs = Set.copyOf(claim.factReferences());
		if (refs.contains(MaritimeAiCanonicalFactReferences.PORT_OVERLAP_REFERENCE)
				&& !"PORT_TIME_OVERLAP_REVIEW".equals(claim.relationshipType())) return "RELATIONSHIP_TYPE_MISMATCH";
		return switch (claim.relationshipType()) {
			case "SCHEDULE_REVIEW" -> hasMotherExpectedArrival(cited)
					&& refs.contains("schedule:MOTHER:PLANNED_ARRIVAL")
					&& refs.contains("schedule:FEEDER:PLANNED_DEPARTURE") ? null : "UNSUPPORTED_SCHEDULE_RELATIONSHIP";
			case "PORT_CONTEXT_REVIEW" -> cited.stream().anyMatch(item -> "PORT_CONTEXT".equals(item.associationScope()))
					? null : "UNSUPPORTED_PORT_CONTEXT_RELATIONSHIP";
			case "PORT_TIME_OVERLAP_REVIEW" -> cited.stream().anyMatch(item -> "PORT_CONTEXT".equals(item.associationScope()))
					&& refs.contains(MaritimeAiCanonicalFactReferences.PORT_OVERLAP_REFERENCE) ? null : "UNSUPPORTED_PORT_TIME_OVERLAP";
			case "REGIONAL_WEATHER_CONTEXT" -> cited.stream().anyMatch(item -> "REGIONAL_CONTEXT".equals(item.associationScope()))
					? null : "UNSUPPORTED_REGIONAL_WEATHER_RELATIONSHIP";
			default -> "UNKNOWN_RELATIONSHIP_TYPE";
		};
	}
	private static boolean hasMotherExpectedArrival(List<MaritimeEvidence> cited) {
		return cited.stream().anyMatch(item -> "MOTHER".equals(item.vesselRole())
				&& "EXPECTED_ARRIVAL".equals(item.observationType()));
	}

	private static boolean hasContextOnlySource(List<MaritimeEvidence> evidence) {
		return evidence.stream().map(MaritimeEvidence::associationScope)
				.anyMatch(scope -> "PORT_CONTEXT".equals(scope) || "REGIONAL_CONTEXT".equals(scope));
	}
	private static boolean hasSyntheticNotice(List<MaritimeEvidence> evidence) {
		return evidence.stream().anyMatch(item -> "SYNTHETIC_DEMONSTRATION".equals(item.sourceMode())
				&& "MPA_OFFICIAL_NOTICE".equals(item.sourceType()));
	}

	private static Set<String> known(List<String> values) {
		return values.stream().filter(value -> value != null && !value.isBlank() && !"UNKNOWN".equals(value))
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	private static boolean hasKnown(List<String> values) { return !known(values).isEmpty(); }
	private static String singleKnown(List<String> values) {
		Set<String> known = known(values);
		return known.size() == 1 ? known.iterator().next() : null;
	}

	private static boolean mentionsFeederArrivalOrRevision(String text) {
		return FEEDER_ARRIVAL_OR_REVISION.matcher(text).find();
	}

	private static boolean mentionsMotherArrivalOrRevision(String text) {
		return MOTHER_ARRIVAL_OR_REVISION.matcher(text).find();
	}

	private static boolean mentionsFeederDeparture(String text) { return FEEDER_DEPARTURE.matcher(text).find(); }
	private static boolean mentionsMotherDeparture(String text) { return MOTHER_DEPARTURE.matcher(text).find(); }
	private static boolean mentionsPlannedFeederDeparture(String text) { return PLANNED_FEEDER_DEPARTURE.matcher(text).find(); }
	private static boolean mentionsObservedFeederDeparture(String text) { return OBSERVED_FEEDER_DEPARTURE.matcher(text).find(); }
	private static boolean hasExpectedFeederDepartureEvidence(List<MaritimeEvidence> evidence) {
		return evidence.stream().anyMatch(item -> "FEEDER".equals(item.vesselRole())
				&& "EXPECTED_DEPARTURE".equals(item.observationType()));
	}
	private static boolean containsGeneratedFactualDetail(String text, MaritimeAiEvidencePackage input,
			MaritimeAiClaim claim, List<MaritimeEvidence> cited) {
		if (text == null) return false;
		return generatedDetailViolation(text, input, claim, cited) != null;
	}
	private static String generatedDetailViolation(String text, MaritimeAiEvidencePackage input,
			MaritimeAiClaim claim, List<MaritimeEvidence> cited) {
		if (text == null) return null;
		Matcher duration = FACTUAL_DURATION.matcher(text);
		if (duration.find()) {
			if (!isExactSupportedRevisionDuration(text, claim, cited)) {
				return UNSUPPORTED_REVISION_QUALIFIER.matcher(text).find()
						? "UNSUPPORTED_REVISION_QUALIFIER" : "UNSTRUCTURED_FACTUAL_DETAIL";
			}
			String withoutDuration = duration.replaceFirst("");
			if (FACTUAL_NUMBER.matcher(withoutDuration).find()) return "UNSTRUCTURED_FACTUAL_DETAIL";
		} else if (FACTUAL_NUMBER.matcher(text).find()) return "UNSTRUCTURED_FACTUAL_DETAIL";
		String lower = text.toLowerCase();
		if (input.shipmentContext() == null) return null;
		return containsIdentity(lower, input.shipmentContext().motherVessel())
				|| containsIdentity(lower, input.shipmentContext().feederVessel()) ? "UNSTRUCTURED_FACTUAL_DETAIL" : null;
	}
	private static boolean isExactSupportedRevisionDuration(String text, MaritimeAiClaim claim, List<MaritimeEvidence> cited) {
		if (claim == null || !"MOTHER".equals(claim.vesselRole()) || !"EXPECTED_ARRIVAL".equals(claim.observationType())
				|| claim.factReferences() == null || cited.size() != 1) return false;
		MaritimeEvidence evidence = cited.getFirst();
		if (!"MPA_ETA_REVISION".equals(evidence.sourceType()) || !"MOTHER".equals(evidence.vesselRole())
				|| !"EXPECTED_ARRIVAL".equals(evidence.observationType())
				|| !claim.factReferences().contains("evidence:" + evidence.evidenceId())) return false;
		Matcher matcher = REVISION_DURATION.matcher(text);
		if (!matcher.find() || UNSUPPORTED_REVISION_QUALIFIER.matcher(text).find()) return false;
		Long quantity = parseQuantity(matcher.group(1));
		Long unitMinutes = unitMinutes(matcher.group(2));
		if (matcher.find()) return false;
		if (quantity == null || unitMinutes == null) return false;
		try {
			long signedMinutes = Duration.between(OffsetDateTime.parse(evidence.previousValue()),
					OffsetDateTime.parse(evidence.currentValue())).toMinutes();
			if (REVISION_DELAY_DIRECTION.matcher(text).find() && signedMinutes <= 0) return false;
			if (REVISION_EARLIER_DIRECTION.matcher(text).find() && signedMinutes >= 0) return false;
			return Math.multiplyExact(quantity, unitMinutes) == Math.abs(signedMinutes);
		} catch (RuntimeException ignored) { return false; }
	}
	private static Long parseQuantity(String value) {
		try { return Long.parseLong(value); }
		catch (NumberFormatException ignored) { return WRITTEN_NUMBERS.get(value.toLowerCase(Locale.ROOT)); }
	}
	private static Long unitMinutes(String unit) {
		return switch (unit.toLowerCase(Locale.ROOT)) {
			case "minute", "minutes" -> 1L;
			case "hour", "hours" -> 60L;
			case "day", "days" -> 24L * 60L;
			default -> null;
		};
	}
	private static boolean containsIdentity(String text, MaritimeAiShipmentContext.Vessel vessel) {
		return vessel != null && vessel.name() != null && !vessel.name().isBlank()
				&& text.contains(vessel.name().toLowerCase());
	}
	private static boolean hasPlannedFeederDeparture(MaritimeAiShipmentContext context) {
		return context != null && context.feederVessel() != null && context.feederVessel().plannedTime() != null
				&& "FEEDER".equals(context.feederVessel().role())
				&& "PLANNED_DEPARTURE".equals(context.feederVessel().plannedMovementType());
	}

	private static boolean claimsAllSourcesAreVerified(String text) {
		String lower = text.toLowerCase();
		return (lower.contains("all evidence") || lower.contains("every evidence") || lower.contains("all source"))
				&& lower.contains("verified")
				&& (lower.contains("association") || lower.contains("linked to the shipment") || lower.contains("linked to shipment"));
	}

	private static boolean callsSyntheticNoticeOfficially(String text) {
		return text.contains("official") && text.contains("notice")
				&& !text.contains("synthetic official") && !text.contains("fictional official") && !text.contains("official-notice-style");
	}

	private static boolean claimsShipmentImpact(String text) {
		String lower = text.toLowerCase();
		return lower.contains("shipment impact") || lower.contains("shipment disruption") || lower.contains("shipment delay")
				|| lower.contains("this shipment is") || lower.contains("this shipment will");
	}

	private static boolean referencesShipmentAsFact(String statementText, String applicabilityText) {
		String statement = statementText == null ? "" : statementText.toLowerCase();
		String applicability = applicabilityText == null ? "" : applicabilityText.toLowerCase();
		boolean marksUncertainty = statement.contains("unverified") || statement.contains("not verified")
				|| statement.contains("conditional") || statement.contains("if the association");
		return (!marksUncertainty && (statement.contains("shipment") || statement.contains("cargo")))
				|| applicability.contains("shipment_specific");
	}

	private static boolean claimsMissedConnection(String text) {
		String statement = text == null ? "" : text.toLowerCase();
		return statement.contains("missed feeder connection") || statement.contains("feeder connection was missed")
				|| statement.contains("connection has been missed") || statement.contains("will miss the connection");
	}

	private static boolean claimsCausation(String text) {
		String statement = text == null ? "" : text.toLowerCase();
		return statement.contains(" caused ") || statement.contains("caused by");
	}

	private static boolean claimsCargoAsFact(String text) {
		String statement = text == null ? "" : text.toLowerCase();
		return statement.contains("cargo") && !statement.contains("no cargo")
				&& !statement.contains("cargo association") && !statement.contains("cargo is not");
	}
}
