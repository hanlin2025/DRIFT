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
	private static final Pattern ISO_OFFSET_DATE_TIME = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?(?:Z|[+-]\\d{2}:\\d{2})\\b");
	private static final Pattern FACTUAL_DURATION = Pattern.compile("(?i)\\b(?:an?|one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s+(?:minute|minutes|hour|hours|day|days)\\b");
	private static final Pattern REVISION_DURATION = Pattern.compile("(?i)\\b(?:revis(?:ed|ion)|change(?:d)?|delay(?:ed)?)\\b[^.!?;]{0,48}?\\b(an?|one|two|three|four|five|six|seven|eight|nine|ten|\\d+)\\s+(minute|minutes|hour|hours|day|days)\\b");
	private static final Pattern UNSUPPORTED_REVISION_QUALIFIER = Pattern.compile("(?i)\\b(?:additional|further|another|subsequent)\\b");
	private static final Pattern REVISION_DELAY_DIRECTION = Pattern.compile("(?i)\\bdelay(?:ed)?\\b");
	private static final Pattern REVISION_EARLIER_DIRECTION = Pattern.compile("(?i)\\b(?:earlier|advance(?:d)?)\\b");
	private static final Pattern RISK_OUTCOME_CLAIM = Pattern.compile("(?i)\\b(?:confirmed|actual|will|would|has|had|is|was|might|may)\\s+(?:be\\s+)?(?:delay(?:ed)?|late|miss(?:ed|es)?|lost)\\b|\\b(?:shipment|vessel|arrival)\\s+(?:is|was|will be|has been)\\s+(?:delay(?:ed)?|late)\\b|\\b(?:business|financial)\\s+loss\\b|\\blost\\s+revenue\\b|\\b(?:penalt(?:y|ies)|demurrage|compensation|delivery outcome|cargo outcome)\\b|\\b(?:probabilit(?:y|ies)|chance|odds)\\s+(?:of|that)\\b|\\b(?:predict|forecast|expect)\\b[^.!?]{0,60}\\b(?:delay|late|miss(?:ed|es)?)\\b");
	private static final Pattern RISK_OUTCOME_CLAUSE_BREAK = Pattern.compile("(?i)[,;.!?]|\\b(?:but|however|yet|nevertheless|and|although|though|whereas|so)\\b");
	private static final Pattern RISK_OUTCOME_NEGATION_BEFORE = Pattern.compile("(?i)\\b(?:no|not|never|cannot|can't|does not|doesn't|without|unconfirmed|unverified)\\b(?:\\s+[\\p{L}\\p{M}'’-]+){0,2}\\s*$");
	private static final Pattern RISK_OUTCOME_UNSUPPORTED_BEFORE = Pattern.compile("(?i)\\b(?:no\\s+(?:clear\\s+)?(?:evidence|indication|basis|support|confirmation|proof)|not enough\\s+(?:evidence|information)|(?:does not|doesn't|cannot|can't)\\s+(?:establish|support|show|indicate|confirm|prove|predict|forecast|demonstrate))\\b[^.!?;,]{0,60}$");
	private static final Pattern RISK_OUTCOME_UNCERTAINTY_AFTER = Pattern.compile("(?i)\\b(?:(?:[\\p{L}\\p{M}'’-]+\\s+){0,5})(?:(?:is|are|was|were|remain(?:s)?|has been|have been)\\s+)?(?:unknown|uncertain|unclear|unavailable|unsupported|unestablished|uncalibrated|not\\s+(?:known|established|supported|available|calibrated|estimated|provided|confirmed|determined)|cannot\\s+be\\s+(?:known|established|supported|calibrated|estimated|determined))\\b");
	private static final Pattern OPERATIONAL_ACTION = Pattern.compile("(?i)\\b(?:re-?route|divert|cancel|hold|release|reschedule|switch|reassign|expedite|dispatch|approve|reject|book)\\b");
	private static final Pattern BUSINESS_CONSTRAINT_ASSERTION = Pattern.compile("(?i)\\b(?:urgent(?:ly)?|urgency|time[- ]sensitive|(?:final\\s+)?delivery\\s+deadline|commercial\\s+deadline|deadline|due\\s+(?:by|on|before|within)|(?:final\\s+)?delivery\\s+due|priority\\s+(?:handling|shipment))\\b");
	private static final Pattern TRANSSHIPMENT_ARRIVAL_DEADLINE = Pattern.compile("(?i)\\btransshipment[- ]arrival\\s+deadline\\b");
	private static final Pattern POSITIVE_DELAY_OUTCOME = Pattern.compile("(?i)\\bno\\s+predicted\\s+impact\\b|\\b(?:predicted|estimated|expected)\\s+arrival\\b[^.!?;]{0,80}\\bexceed(?:s|ed)?\\b[^.!?;]{0,50}\\bdeadline\\b|\\bexceed(?:s|ed)?\\b[^.!?;]{0,50}\\b(?:recorded\\s+)?transshipment[- ]arrival\\s+deadline\\b");
	private static final Pattern BUSINESS_CONSTRAINT_VERIFICATION_PREFIX = Pattern.compile("(?i)\\b(?:confirm|verify|check|establish|determine|ask)\\b[^.!?]{0,80}\\b(?:whether|if)\\b[^.!?]{0,80}$");
	private static final Pattern BUSINESS_CONSTRAINT_MISSING_PREFIX = Pattern.compile("(?i)\\bno\\s+(?:(?:final|commercial|delivery|transshipment(?:[- ]arrival)?|shipment|recorded)\\s+){0,3}$");
	private static final Pattern BUSINESS_CONSTRAINT_MISSING_STATUS = Pattern.compile("(?i)^\\s+(?:is\\s+)?(?:recorded|provided|specified|available|known)\\b");
	private static final Pattern BUSINESS_CONSTRAINT_UNKNOWN_STATUS = Pattern.compile("(?i)^\\s+(?:is\\s+)?(?:unknown|unavailable|unspecified|unrecorded|not\\s+(?:known|available|provided|recorded|specified))\\b");
	private static final Pattern HUMAN_REVIEW_PARTICIPANT = Pattern.compile("(?i)\\b(?:human|person|people|operator|operations\\s+(?:team|staff)|agent|coordinator|planner|dispatcher|staff|team)\\b");
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
		List<String> authoredText = authoredText(output);
		if (authoredText.stream().anyMatch(MaritimeAiResponseValidator::claimsCargoAsFact)) return "UNSUPPORTED_CARGO_ASSERTION";
		if (claimsUnsupportedBusinessConstraint(output, input))
			return "UNSUPPORTED_BUSINESS_CONSTRAINT";
		if (claimsUnsupportedDelayConclusion(output, input)) return "UNSUPPORTED_DELAY_COMPARISON";
		if (authoredText.stream().anyMatch(MaritimeAiResponseValidator::claimsMissedConnection)) return "UNSUPPORTED_MISSED_CONNECTION";
		if (authoredText.stream().anyMatch(MaritimeAiResponseValidator::claimsCausation)) return "UNSUPPORTED_CAUSAL_CLAIM";
		if (authoredText.stream().anyMatch(MaritimeAiResponseValidator::containsOperationalAction))
			return "UNSUPPORTED_OPERATIONAL_DECISION";
		if (!"VERIFIED".equals(input.shipmentAssociationConfidence()) && referencesShipmentAsFact(output.summary(), "")) {
			return "UNVERIFIED_SHIPMENT_ASSOCIATION";
		}
		boolean hasRiskBufferReview = output.claims().stream().filter(java.util.Objects::nonNull)
				.anyMatch(claim -> "RISK_BUFFER_REVIEW".equals(claim.relationshipType()));
		if (hasRiskBufferReview) {
			if (output.followUpConsiderations().stream().filter(java.util.Objects::nonNull)
					.noneMatch(MaritimeAiResponseValidator::isHumanVerificationFollowUp)) return "MISSING_HUMAN_RISK_FOLLOW_UP";
			for (String text : java.util.stream.Stream.concat(output.uncertainties().stream(), output.followUpConsiderations().stream())
					.filter(java.util.Objects::nonNull).toList()) {
				if (generatedDetailViolation(text, input, null, List.of()) != null) return "UNSTRUCTURED_FACTUAL_DETAIL";
			}
		}
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
			if (!"VERIFIED".equals(input.shipmentAssociationConfidence())
					&& referencesShipmentAsFact(claim.statement(), claim.applicability())) return "UNVERIFIED_SHIPMENT_ASSOCIATION";

			for (MaritimeEvidence source : cited) {
				if ("INCOMPATIBLE".equals(source.compatibilityState()) || "UNAVAILABLE".equals(source.sourceMode())
						|| "STALE".equals(source.freshness()) || !source.evidenceSufficient()) return "INELIGIBLE_CITED_EVIDENCE";
			}
			String structuredGrounding = validateStructuredGrounding(claim, cited, input);
			if (structuredGrounding != null) return structuredGrounding;
			String relationshipGrounding = validateRelationship(claim, cited, input);
			if (relationshipGrounding != null) return relationshipGrounding;
		}
		if (claimsUnsupportedRiskOutcome(output, input)) return "UNSUPPORTED_OPERATIONAL_IMPACT_CLAIM";
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
	private static String validateRelationship(MaritimeAiClaim claim, List<MaritimeEvidence> cited, MaritimeAiEvidencePackage input) {
		if (!"RISK_BUFFER_REVIEW".equals(claim.relationshipType())
				&& !"DELAY_COMPARISON_REVIEW".equals(claim.relationshipType()) && claim.riskReview() != null)
			return "UNEXPECTED_RISK_REVIEW_CONTEXT";
		if (claim.relationshipType() == null || claim.relationshipType().isBlank()) return null;
		Set<String> refs = claim.factReferences() == null ? Set.of() : Set.copyOf(claim.factReferences());
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
			case "RISK_BUFFER_REVIEW" -> validateRiskRelationship(refs, claim, cited, input);
			case "DELAY_COMPARISON_REVIEW" -> validateDelayComparisonRelationship(refs, claim, cited, input);
			default -> "UNKNOWN_RELATIONSHIP_TYPE";
		};
	}
	private static String validateRiskRelationship(Set<String> refs, MaritimeAiClaim claim,
			List<MaritimeEvidence> cited, MaritimeAiEvidencePackage input) {
		MaritimeAiConnectionRiskContext risk = input.shipmentContext() == null ? null : input.shipmentContext().connectionRisk();
		if (risk == null || !risk.assessed() || risk.riskLevel() == null || risk.riskBasis() == null
				|| !refs.contains("risk:CONNECTION_SEVERITY"))
			return "UNSUPPORTED_RISK_CONTEXT_RELATIONSHIP";
		String selected = risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE
				? "risk:REMAINING_CONNECTION_WINDOW" : "risk:PLANNED_CONNECTION_WINDOW";
		if (!refs.contains(selected)) return "UNSUPPORTED_RISK_CONTEXT_RELATIONSHIP";
		String unselected = risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE
				? "risk:PLANNED_CONNECTION_WINDOW" : "risk:REMAINING_CONNECTION_WINDOW";
		if (refs.contains(unselected)) return "UNSUPPORTED_RISK_CONTEXT_RELATIONSHIP";
		if (risk.arrivalEstimate() != null && risk.arrivalEstimate().state() != MaritimeAiConnectionRiskContext.EstimateState.UNKNOWN
				&& !refs.contains("risk:ARRIVAL_ESTIMATE_FRESHNESS")) return "UNSUPPORTED_RISK_CONTEXT_RELATIONSHIP";
		if (risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE
				&& (!refs.contains("risk:AIS_ESTIMATED_ARRIVAL") || risk.arrivalEstimate() == null
						|| risk.arrivalEstimate().state() != MaritimeAiConnectionRiskContext.EstimateState.AVAILABLE))
			return "UNSUPPORTED_RISK_CONTEXT_RELATIONSHIP";
		if (risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.PLANNED_SHIPMENT_SCHEDULE
				&& risk.arrivalEstimate() != null && risk.arrivalEstimate().state() != MaritimeAiConnectionRiskContext.EstimateState.UNKNOWN
				&& !refs.contains("risk:ARRIVAL_ESTIMATE_NOT_USED")) return "UNSUPPORTED_RISK_CONTEXT_RELATIONSHIP";
		Set<String> citedEvidenceReferences = refs.stream().filter(reference -> reference.startsWith("evidence:"))
				.map(reference -> reference.substring("evidence:".length())).collect(Collectors.toSet());
		Set<String> citedEvidenceIds = cited.stream().map(MaritimeEvidence::evidenceId).collect(Collectors.toSet());
		if (!citedEvidenceReferences.equals(citedEvidenceIds))
			return "UNSUPPORTED_RISK_CONTEXT_RELATIONSHIP";
		if (!expectedRiskReview(risk).equals(claim.riskReview())) return "RISK_REVIEW_CONTEXT_MISMATCH";
		if (!"CONTEXTUAL".equals(claim.applicability()) && !"UNCERTAIN".equals(claim.applicability()))
			return "RISK_REVIEW_NOT_TENTATIVE";
		String statement = claim.statement().toLowerCase(Locale.ROOT);
		if (!containsAny(statement, "review", "check", "verify", "consider", "assess", "monitor")
				|| !containsAny(statement, "recorded", "documented", "revision", "observation", "notice", "weather", "evidence", "source")
				|| !statesSeverity(statement, risk.riskLevel())) return "RISK_REVIEW_NOT_ACTIONABLE";
		if (risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.PLANNED_SHIPMENT_SCHEDULE) {
			if (!statement.contains("planned") || !containsAny(statement, "window", "schedule")) return "RISK_REVIEW_WINDOW_BASIS_MISMATCH";
		} else if (!statement.contains("ais") || !statement.contains("remaining")
				|| !containsAny(statement, "window", "buffer")) return "RISK_REVIEW_WINDOW_BASIS_MISMATCH";
		if (!describesFreshness(statement, risk.arrivalEstimate() == null
				? MaritimeAiConnectionRiskContext.EstimateState.UNKNOWN : risk.arrivalEstimate().state()))
			return "RISK_REVIEW_FRESHNESS_MISMATCH";
		MaritimeAiClaim.RiskReview.SelectedWindowState windowState = expectedRiskReview(risk).selectedWindowState();
		if (windowState == MaritimeAiClaim.RiskReview.SelectedWindowState.NO_REMAINING_WINDOW
				&& !containsAny(statement, "no positive buffer", "no remaining window", "no available buffer"))
			return "RISK_REVIEW_WINDOW_STATE_MISMATCH";
		if (windowState == MaritimeAiClaim.RiskReview.SelectedWindowState.INVALID && !containsAny(statement, "invalid", "not valid"))
			return "RISK_REVIEW_WINDOW_STATE_MISMATCH";
		if (windowState == MaritimeAiClaim.RiskReview.SelectedWindowState.INCOMPLETE && !containsAny(statement, "incomplete", "not established", "unknown"))
			return "RISK_REVIEW_WINDOW_STATE_MISMATCH";
		return null;
	}
	private static String validateDelayComparisonRelationship(Set<String> refs, MaritimeAiClaim claim,
			List<MaritimeEvidence> cited, MaritimeAiEvidencePackage input) {
		MaritimeAiBusinessContext business = input.shipmentContext() == null ? null : input.shipmentContext().businessContext();
		if (business == null || business.deadline() == null || business.comparison() == null
				|| business.comparison().state() == MaritimeAiBusinessContext.ComparisonState.UNKNOWN)
			return "UNSUPPORTED_DELAY_COMPARISON";
		Set<String> evidenceRefs = refs.stream().filter(reference -> reference.startsWith("evidence:"))
				.map(reference -> reference.substring("evidence:".length())).collect(Collectors.toSet());
		if (cited.isEmpty() || !evidenceRefs.equals(cited.stream().map(MaritimeEvidence::evidenceId).collect(Collectors.toSet())))
			return "UNSUPPORTED_DELAY_COMPARISON";
		if (refs.stream().anyMatch(reference -> reference.startsWith("risk:")) || claim.riskReview() != null) {
			String riskValidation = validateRiskRelationship(refs, claim, cited, input);
			if (riskValidation != null) return riskValidation;
		}
		MaritimeAiBusinessContext.Deadline deadline = business.deadline();
		MaritimeAiBusinessContext.Comparison comparison = business.comparison();
		if (deadline.state() == MaritimeAiBusinessContext.DeadlineState.RECORDED) {
			if (deadline.instant() == null || deadline.recordedTransshipmentPort() == null || deadline.applicableToCurrentPort() == null
					|| !hasAllowedReferences(refs, MaritimeAiCanonicalFactReferences.allowed(input),
							MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE,
							MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_PORT,
							MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_APPLICABILITY))
				return "UNSUPPORTED_BUSINESS_CONSTRAINT";
			if (comparison.comparedDeadline() == null || !comparison.comparedDeadline().toInstant().equals(deadline.instant().toInstant())
					|| !java.util.Objects.equals(comparison.recordedTransshipmentPort(), deadline.recordedTransshipmentPort()))
				return "UNSUPPORTED_DELAY_COMPARISON";
		} else if (deadline.state() == MaritimeAiBusinessContext.DeadlineState.NOT_RECORDED) {
			if (!hasAllowedReferences(refs, MaritimeAiCanonicalFactReferences.allowed(input),
					MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_NOT_RECORDED)) return "UNSUPPORTED_BUSINESS_CONSTRAINT";
		} else return "UNSUPPORTED_DELAY_COMPARISON";

		if (comparison.state() == MaritimeAiBusinessContext.ComparisonState.AVAILABLE) {
			if (comparison.outcome() == null || comparison.unavailableReason() != null
					|| deadline.state() != MaritimeAiBusinessContext.DeadlineState.RECORDED
					|| !Boolean.TRUE.equals(deadline.applicableToCurrentPort())
					|| !hasAllowedReferences(refs, MaritimeAiCanonicalFactReferences.allowed(input),
							MaritimeAiCanonicalFactReferences.COMPARISON_OUTCOME,
							MaritimeAiCanonicalFactReferences.COMPARISON_ESTIMATED_ARRIVAL,
							MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_DEADLINE,
							MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_PORT))
				return "UNSUPPORTED_DELAY_COMPARISON";
		} else if (comparison.state() == MaritimeAiBusinessContext.ComparisonState.UNAVAILABLE) {
			if (comparison.unavailableReason() == null || comparison.outcome() != null
					|| !hasAllowedReferences(refs, MaritimeAiCanonicalFactReferences.allowed(input),
							MaritimeAiCanonicalFactReferences.COMPARISON_UNAVAILABLE_REASON))
				return "UNSUPPORTED_DELAY_COMPARISON";
			if (comparison.estimatedArrivalAt() != null && !hasAllowedReferences(refs, MaritimeAiCanonicalFactReferences.allowed(input),
					MaritimeAiCanonicalFactReferences.COMPARISON_ESTIMATED_ARRIVAL)) return "UNSUPPORTED_DELAY_COMPARISON";
			if (comparison.comparedDeadline() != null && !hasAllowedReferences(refs, MaritimeAiCanonicalFactReferences.allowed(input),
					MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_DEADLINE,
					MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_PORT)) return "UNSUPPORTED_DELAY_COMPARISON";
		} else return "UNSUPPORTED_DELAY_COMPARISON";

		String statement = claim.statement().toLowerCase(Locale.ROOT);
		if (!TRANSSHIPMENT_ARRIVAL_DEADLINE.matcher(statement).find()
				|| !containsAny(statement, "review", "check", "verify", "consider", "assess", "monitor")
				|| !containsAny(statement, "evidence", "observation", "revision", "source"))
			return "DELAY_COMPARISON_NOT_ACTIONABLE";
		if (deadline.state() == MaritimeAiBusinessContext.DeadlineState.RECORDED) {
			if (Boolean.TRUE.equals(deadline.applicableToCurrentPort())
					&& !containsAny(statement, "applicable", "applies to the current transshipment port", "current transshipment port"))
				return "BUSINESS_APPLICABILITY_MISMATCH";
			if (Boolean.FALSE.equals(deadline.applicableToCurrentPort())
					&& !containsAny(statement, "different port", "port differs", "port mismatch", "not applicable", "does not apply"))
				return "BUSINESS_APPLICABILITY_MISMATCH";
		}
		if (comparison.state() == MaritimeAiBusinessContext.ComparisonState.UNAVAILABLE
				&& !describesUnavailableComparison(statement, comparison.unavailableReason()))
			return "DELAY_COMPARISON_REASON_MISMATCH";
		return null;
	}
	private static boolean hasAllowedReferences(Set<String> claimReferences, Set<String> allowedReferences, String... required) {
		return claimReferences.containsAll(List.of(required)) && allowedReferences.containsAll(List.of(required));
	}
	private static boolean describesUnavailableComparison(String statement, com.drift.backend.shipment.DelayComparison.Reason reason) {
		return switch (reason) {
			case INCOMPATIBLE_MILESTONE -> containsAny(statement, "different port", "port differs", "port mismatch", "not applicable", "does not apply");
			case MISSING_PREDICTION -> containsAny(statement, "unavailable", "missing estimate", "no estimate");
			case MISSING_CONSTRAINT -> containsAny(statement, "not recorded", "no deadline", "unrecorded", "no transshipment-arrival deadline");
			case STALE_PREDICTION -> containsAny(statement, "stale", "outdated");
		};
	}
	private static MaritimeAiClaim.RiskReview expectedRiskReview(MaritimeAiConnectionRiskContext risk) {
		MaritimeAiClaim.RiskReview.SelectedWindowState windowState = MaritimeAiClaim.RiskReview.SelectedWindowState.INCOMPLETE;
		if (risk.assessed() && risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE
				&& risk.remainingWindow() != null) {
			windowState = "AVAILABLE".equals(risk.remainingWindow().status())
					? MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE
					: "MISSED".equals(risk.remainingWindow().status())
							? MaritimeAiClaim.RiskReview.SelectedWindowState.NO_REMAINING_WINDOW
							: MaritimeAiClaim.RiskReview.SelectedWindowState.INCOMPLETE;
		} else if (risk.assessed() && risk.riskBasis() == MaritimeAiConnectionRiskContext.RiskBasis.PLANNED_SHIPMENT_SCHEDULE
				&& risk.plannedWindow() != null) {
			windowState = switch (risk.plannedWindow().state()) {
				case AVAILABLE -> MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE;
				case INVALID -> MaritimeAiClaim.RiskReview.SelectedWindowState.INVALID;
				case INCOMPLETE -> MaritimeAiClaim.RiskReview.SelectedWindowState.INCOMPLETE;
			};
		}
		return new MaritimeAiClaim.RiskReview(risk.riskLevel(), risk.riskBasis(), windowState,
				risk.arrivalEstimate() == null ? MaritimeAiConnectionRiskContext.EstimateState.UNKNOWN : risk.arrivalEstimate().state());
	}
	private static boolean describesFreshness(String statement, MaritimeAiConnectionRiskContext.EstimateState state) {
		return switch (state) {
			case AVAILABLE -> !containsWord(statement, "unavailable")
					&& !containsWholePhrase(statement, "not available")
					&& !containsWholePhrase(statement, "not fresh")
					&& (containsPositiveWord(statement, "fresh") || containsPositiveWord(statement, "available"));
			case STALE -> containsWord(statement, "stale") || containsWord(statement, "outdated");
			case UNAVAILABLE -> containsWord(statement, "unavailable") || containsWholePhrase(statement, "not available");
			case UNKNOWN -> containsWord(statement, "unknown") || containsWholePhrase(statement, "not known");
		};
	}
	private static boolean statesSeverity(String statement, com.drift.backend.shipment.ShipmentRisk.Level severity) {
		for (com.drift.backend.shipment.ShipmentRisk.Level candidate : com.drift.backend.shipment.ShipmentRisk.Level.values()) {
			if (containsWord(statement, candidate.name().toLowerCase(Locale.ROOT)) && candidate != severity) return false;
		}
		return containsWord(statement, severity.name().toLowerCase(Locale.ROOT));
	}
	private static boolean containsAny(String text, String... phrases) {
		for (String phrase : phrases) if (text.contains(phrase)) return true;
		return false;
	}
	private static boolean containsWord(String text, String word) {
		return Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b").matcher(text).find();
	}
	private static boolean containsPositiveWord(String text, String word) {
		Matcher matches = Pattern.compile("(?i)\\b" + Pattern.quote(word) + "\\b").matcher(text);
		Pattern negated = Pattern.compile("(?i)\\b(?:not|never|no|without)\\b(?:\\s+[\\p{L}\\p{M}'’-]+){0,2}\\s*$");
		Pattern clauseBreak = Pattern.compile("(?i)[,;.!?]|\\b(?:but|however|yet|nevertheless|although|though|whereas)\\b");
		while (matches.find()) {
			int clauseStart = 0;
			Matcher breaks = clauseBreak.matcher(text);
			while (breaks.find() && breaks.end() <= matches.start()) clauseStart = breaks.end();
			if (!negated.matcher(text.substring(clauseStart, matches.start())).find()) return true;
		}
		return false;
	}
	private static boolean containsWholePhrase(String text, String phrase) {
		String pattern = Pattern.quote(phrase).replace("\\E \\Q", "\\E\\s+\\Q");
		return Pattern.compile("(?i)\\b" + pattern + "\\b").matcher(text).find();
	}
	private static List<String> authoredText(MaritimeAiInterpretation output) {
		List<String> text = new java.util.ArrayList<>();
		text.add(output.summary());
		if (output.claims() != null) output.claims().stream().filter(java.util.Objects::nonNull)
				.map(MaritimeAiClaim::statement).filter(java.util.Objects::nonNull).forEach(text::add);
		if (output.uncertainties() != null) output.uncertainties().stream().filter(java.util.Objects::nonNull).forEach(text::add);
		if (output.followUpConsiderations() != null) output.followUpConsiderations().stream()
				.filter(java.util.Objects::nonNull).forEach(text::add);
		return text;
	}
	static boolean claimsUnsupportedBusinessConstraint(MaritimeAiInterpretation output, Set<String> allowedFactReferences) {
		return claimsUnsupportedBusinessConstraint(output, allowedFactReferences, null);
	}
	static boolean claimsUnsupportedBusinessConstraint(MaritimeAiInterpretation output, MaritimeAiEvidencePackage input) {
		return claimsUnsupportedBusinessConstraint(output, MaritimeAiCanonicalFactReferences.allowed(input),
				input.shipmentContext() == null ? null : input.shipmentContext().businessContext());
	}
	private static boolean claimsUnsupportedBusinessConstraint(MaritimeAiInterpretation output,
			Set<String> allowedFactReferences, MaritimeAiBusinessContext businessContext) {
		if (output == null) return false;
		if (hasUnsupportedBusinessConstraint(output.summary(), null, allowedFactReferences, businessContext)) return true;
		if (output.claims() != null && output.claims().stream().filter(java.util.Objects::nonNull)
				.anyMatch(claim -> hasUnsupportedBusinessConstraint(claim.statement(), claim.factReferences(), allowedFactReferences, businessContext))) return true;
		if (output.uncertainties() != null && output.uncertainties().stream()
				.anyMatch(text -> hasUnsupportedBusinessConstraint(text, null, allowedFactReferences, businessContext))) return true;
		return output.followUpConsiderations() != null && output.followUpConsiderations().stream()
				.anyMatch(text -> hasUnsupportedBusinessConstraint(text, null, allowedFactReferences, businessContext));
	}
	private static boolean hasUnsupportedBusinessConstraint(String text, List<String> claimReferences,
			Set<String> allowedFactReferences, MaritimeAiBusinessContext businessContext) {
		if (text == null) return false;
		for (String clause : text.split("(?i)[,;.!?]|\\b(?:but|however|yet|and|or)\\b")) {
			Matcher assertion = BUSINESS_CONSTRAINT_ASSERTION.matcher(clause);
			while (assertion.find()) {
				String constraint = assertion.group();
				if (BUSINESS_CONSTRAINT_VERIFICATION_PREFIX.matcher(clause.substring(0, assertion.start())).find()) continue;
				if (hasExplicitlyMissingContext(clause, assertion.start(), assertion.end())) {
					if (missingContextContradictsOrLacksReference(clause, claimReferences, allowedFactReferences, businessContext)) return true;
					continue;
				}
				if (hasAuthorizedBusinessReference(clause, constraint, claimReferences, allowedFactReferences, businessContext)) continue;
				return true;
			}
		}
		return false;
	}
	private static boolean missingContextContradictsOrLacksReference(String clause, List<String> claimReferences,
			Set<String> allowedFactReferences, MaritimeAiBusinessContext businessContext) {
		if (businessContext == null || businessContext.deadline() == null
				|| !TRANSSHIPMENT_ARRIVAL_DEADLINE.matcher(clause).find()) return false;
		MaritimeAiBusinessContext.Deadline deadline = businessContext.deadline();
		if (deadline.state() == MaritimeAiBusinessContext.DeadlineState.RECORDED) return true;
		if (deadline.state() == MaritimeAiBusinessContext.DeadlineState.NOT_RECORDED) {
			String missingReference = MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_NOT_RECORDED;
			return claimReferences == null || !claimReferences.contains(missingReference) || !allowedFactReferences.contains(missingReference);
		}
		return false;
	}
	private static boolean hasExplicitlyMissingContext(String clause, int assertionStart, int assertionEnd) {
		String prefix = clause.substring(Math.max(0, assertionStart - 48), assertionStart);
		String suffix = clause.substring(assertionEnd, Math.min(clause.length(), assertionEnd + 48));
		return BUSINESS_CONSTRAINT_UNKNOWN_STATUS.matcher(suffix).find()
				|| (BUSINESS_CONSTRAINT_MISSING_PREFIX.matcher(prefix).find()
						&& BUSINESS_CONSTRAINT_MISSING_STATUS.matcher(suffix).find());
	}
	/** An assertion can be grounded once an authorized typed business fact is supplied and cited. */
	private static boolean hasAuthorizedBusinessReference(String clause, String constraint, List<String> claimReferences,
			Set<String> allowedFactReferences, MaritimeAiBusinessContext businessContext) {
		if (claimReferences == null || allowedFactReferences == null || businessContext == null
				|| isUrgencyConstraint(constraint) || !TRANSSHIPMENT_ARRIVAL_DEADLINE.matcher(clause).find()) return false;
		MaritimeAiBusinessContext.Deadline deadline = businessContext.deadline();
		if (deadline == null || deadline.state() != MaritimeAiBusinessContext.DeadlineState.RECORDED
				|| deadline.instant() == null || deadline.recordedTransshipmentPort() == null
				|| deadline.applicableToCurrentPort() == null) return false;
		Set<String> required = Set.of(MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE,
				MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_PORT,
				MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_APPLICABILITY);
		if (!claimReferences.containsAll(required) || !allowedFactReferences.containsAll(required)) return false;
		String lower = clause.toLowerCase(Locale.ROOT);
		boolean saysNotApplicable = containsAny(lower, "different port", "not applicable", "does not apply", "doesn't apply");
		boolean saysApplicable = !saysNotApplicable
				&& containsAny(lower, "applicable", "applies to the current transshipment port", "current transshipment port");
		return Boolean.TRUE.equals(deadline.applicableToCurrentPort()) ? saysApplicable && !saysNotApplicable
				: Boolean.FALSE.equals(deadline.applicableToCurrentPort()) && saysNotApplicable && !saysApplicable;
	}
	private static boolean isUrgencyConstraint(String constraint) {
		String normalized = constraint.toLowerCase(Locale.ROOT);
		return normalized.contains("urg") || normalized.contains("time-sensitive")
				|| normalized.contains("time sensitive") || normalized.contains("priority");
	}
	private static boolean claimsUnsupportedDelayConclusion(MaritimeAiInterpretation output, MaritimeAiEvidencePackage input) {
		if (hasUnsupportedDelayConclusion(output.summary(), null, input)) return true;
		if (output.claims() != null && output.claims().stream().filter(java.util.Objects::nonNull)
				.anyMatch(claim -> hasUnsupportedDelayConclusion(claim.statement(), claim, input))) return true;
		if (output.uncertainties() != null && output.uncertainties().stream()
				.anyMatch(text -> hasUnsupportedDelayConclusion(text, null, input))) return true;
		return output.followUpConsiderations() != null && output.followUpConsiderations().stream()
				.anyMatch(text -> hasUnsupportedDelayConclusion(text, null, input));
	}
	private static boolean hasUnsupportedDelayConclusion(String text, MaritimeAiClaim claim, MaritimeAiEvidencePackage input) {
		if (text == null) return false;
		MaritimeAiBusinessContext business = input.shipmentContext() == null ? null : input.shipmentContext().businessContext();
		MaritimeAiBusinessContext.Comparison comparison = business == null ? null : business.comparison();
		Set<String> allowed = MaritimeAiCanonicalFactReferences.allowed(input);
		Matcher matcher = POSITIVE_DELAY_OUTCOME.matcher(text);
		while (matcher.find()) {
			if (negatesDelayConclusion(text, matcher.start(), matcher.end())) continue;
			if (claim == null || !"DELAY_COMPARISON_REVIEW".equals(claim.relationshipType())
					|| comparison == null || comparison.state() != MaritimeAiBusinessContext.ComparisonState.AVAILABLE
					|| comparison.outcome() == null || claim.factReferences() == null
					|| !claim.factReferences().contains(MaritimeAiCanonicalFactReferences.COMPARISON_OUTCOME)
					|| !allowed.contains(MaritimeAiCanonicalFactReferences.COMPARISON_OUTCOME)) return true;
			boolean noImpact = matcher.group().toLowerCase(Locale.ROOT).contains("no predicted impact");
			if (noImpact != (comparison.outcome() == com.drift.backend.shipment.DelayComparison.Outcome.NO_PREDICTED_IMPACT)) return true;
		}
		return false;
	}
	private static boolean negatesDelayConclusion(String text, int start, int end) {
		String prefix = text.substring(Math.max(0, start - 48), start);
		String suffix = text.substring(end, Math.min(text.length(), end + 48));
		return Pattern.compile("(?i)\\b(?:cannot|can't|does not|doesn't|not|never)\\s+(?:infer|claim|conclude|establish|confirm|support|show|indicate|prove)\\s*$")
				.matcher(prefix).find()
				|| Pattern.compile("(?i)^\\s+(?:cannot be concluded|is not established|has not been established|cannot be established|is unsupported)\\b")
						.matcher(suffix).find();
	}
	private static boolean claimsRiskOutcome(String text) {
		if (text == null) return false;
		Matcher matcher = RISK_OUTCOME_CLAIM.matcher(text);
		while (matcher.find()) {
			int clauseStart = 0;
			Matcher breaksBefore = RISK_OUTCOME_CLAUSE_BREAK.matcher(text);
			while (breaksBefore.find() && breaksBefore.start() < matcher.start()) clauseStart = breaksBefore.end();
			int clauseEnd = text.length();
			Matcher breaksAfter = RISK_OUTCOME_CLAUSE_BREAK.matcher(text);
			if (breaksAfter.find(matcher.end())) clauseEnd = breaksAfter.start();
			String clausePrefix = text.substring(clauseStart, matcher.start());
			String clauseSuffix = text.substring(matcher.end(), clauseEnd);
			if (!RISK_OUTCOME_NEGATION_BEFORE.matcher(clausePrefix).find()
					&& !RISK_OUTCOME_UNSUPPORTED_BEFORE.matcher(clausePrefix).find()
					&& !RISK_OUTCOME_UNCERTAINTY_AFTER.matcher(clauseSuffix).find()) return true;
		}
		return false;
	}
	private static boolean claimsUnsupportedRiskOutcome(MaritimeAiInterpretation output, MaritimeAiEvidencePackage input) {
		if (claimsRiskOutcome(output.summary())) return true;
		if (output.uncertainties().stream().filter(java.util.Objects::nonNull).anyMatch(MaritimeAiResponseValidator::claimsRiskOutcome)) return true;
		if (output.followUpConsiderations().stream().filter(java.util.Objects::nonNull).anyMatch(MaritimeAiResponseValidator::claimsRiskOutcome)) return true;
		for (MaritimeAiClaim claim : output.claims()) {
			if (claim == null || !claimsRiskOutcome(claim.statement())) continue;
			List<MaritimeEvidence> cited = input.evidence().stream()
					.filter(item -> claim.evidenceIds().contains(item.evidenceId())).toList();
			if (!isExactSupportedRevisionDuration(claim.statement(), claim, cited)
					|| claimsRiskOutcome(REVISION_DURATION.matcher(claim.statement()).replaceFirst(""))) return true;
		}
		return false;
	}
	private static boolean containsOperationalAction(String text) { return text != null && OPERATIONAL_ACTION.matcher(text).find(); }
	private static boolean isHumanVerificationFollowUp(String text) {
		String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
		return HUMAN_REVIEW_PARTICIPANT.matcher(lower).find()
				&& containsAny(lower, "review", "check", "verify", "compare", "confirm", "reconcile");
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
			if (FACTUAL_NUMBER.matcher(withoutDuration).find() && !supportedBusinessDateTokens(withoutDuration, input, claim))
				return "UNSTRUCTURED_FACTUAL_DETAIL";
		} else if (FACTUAL_NUMBER.matcher(text).find() && !supportedBusinessDateTokens(text, input, claim))
			return "UNSTRUCTURED_FACTUAL_DETAIL";
		String lower = text.toLowerCase();
		if (input.shipmentContext() == null) return null;
		return containsIdentity(lower, input.shipmentContext().motherVessel())
				|| containsIdentity(lower, input.shipmentContext().feederVessel()) ? "UNSTRUCTURED_FACTUAL_DETAIL" : null;
	}
	private static boolean supportedBusinessDateTokens(String text, MaritimeAiEvidencePackage input, MaritimeAiClaim claim) {
		if (claim == null || claim.factReferences() == null || input.shipmentContext() == null
				|| input.shipmentContext().businessContext() == null) return false;
		Set<String> allowed = MaritimeAiCanonicalFactReferences.allowed(input);
		Set<String> refs = Set.copyOf(claim.factReferences());
		MaritimeAiBusinessContext business = input.shipmentContext().businessContext();
		Matcher date = ISO_OFFSET_DATE_TIME.matcher(text);
		int matched = 0;
		while (date.find()) {
			matched++;
			OffsetDateTime stated;
			try { stated = OffsetDateTime.parse(date.group()); }
			catch (RuntimeException invalidDate) { return false; }
			boolean matchesDeadline = business.deadline() != null && business.deadline().instant() != null
					&& stated.toInstant().equals(business.deadline().instant().toInstant())
					&& hasAllowedReferences(refs, allowed, MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE,
							MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_PORT,
							MaritimeAiCanonicalFactReferences.BUSINESS_DEADLINE_APPLICABILITY);
			MaritimeAiBusinessContext.Comparison comparison = business.comparison();
			boolean matchesEstimate = comparison != null && comparison.estimatedArrivalAt() != null
					&& stated.toInstant().equals(comparison.estimatedArrivalAt().toInstant())
					&& hasAllowedReferences(refs, allowed, MaritimeAiCanonicalFactReferences.COMPARISON_ESTIMATED_ARRIVAL);
			boolean matchesComparedDeadline = comparison != null && comparison.comparedDeadline() != null
					&& stated.toInstant().equals(comparison.comparedDeadline().toInstant())
					&& hasAllowedReferences(refs, allowed, MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_DEADLINE,
							MaritimeAiCanonicalFactReferences.COMPARISON_RECORDED_PORT);
			if (!matchesDeadline && !matchesEstimate && !matchesComparedDeadline) return false;
		}
		return matched > 0 && !FACTUAL_NUMBER.matcher(ISO_OFFSET_DATE_TIME.matcher(text).replaceAll("")).find();
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
				|| statement.contains("connection has been missed") || statement.contains("will miss the connection")
				|| statement.contains("connection is missed") || statement.contains("actual missed connection")
				|| statement.contains("confirmed missed connection");
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
