package com.drift.backend.maritimeai;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.drift.backend.shipment.ShipmentRisk;

/**
 * The deliberately bounded English grammar for the controlled scenario demo. It accepts only complete
 * sentence patterns listed here; it does not claim general semantic equivalence.
 */
final class ScenarioMaritimeAiProse {
	private static final List<String> REVISION_NOUNS = List.of("expected-arrival revision", "arrival revision");
	private static final List<String> RISK_RELEVANCE_VERBS = List.of("warrants human review of",
			"gives the operations team a reason to recheck");
	private static final List<String> RISK_RELEVANCE_OBJECTS = List.of("connection buffer", "remaining connection window");
	private static final List<String> BACKEND_RISK_VERBS = List.of("reports", "assesses");
	private static final List<String> DEADLINE_RELEVANCE_VERBS = List.of("warrants review of",
			"gives the operations team a reason to check");
	private static final List<String> DEADLINE_TARGETS = List.of("milestone", "arrival requirement");
	private static final List<String> COMPARISON_VERBS = List.of("shows", "indicates", "reports");
	private static final List<String> ARRIVAL_ADJECTIVES = List.of("predicted", "estimated");
	private static final List<String> ARRIVAL_ARTICLE_PREFIXES = List.of("the ", "");
	private static final String AVAILABLE_WINDOW_STATE = "The AIS-estimated remaining connection window is available.";
	private static final String NO_REMAINING_WINDOW_STATE = "The AIS estimate leaves no positive remaining connection window.";
	private static final List<String> UNCERTAINTY_SUBJECTS = List.of("maritime evidence", "arrival revision");
	private static final List<String> UNCERTAINTY_VERBS = List.of("does not establish", "does not confirm", "cannot establish");
	private static final List<String> UNCERTAINTY_OBJECTS = List.of("an actual arrival", "that the vessel has arrived");
	private static final List<String> ETA_TEAMS = List.of("carrier operations team", "carrier operations staff");
	private static final List<String> CHECK_VERBS = List.of("verify", "confirm", "check");
	private static final List<String> ETA_TARGETS = List.of(
			"whether the latest arrival estimate remains current at the scenario cutoff",
			"the latest arrival estimate at the scenario cutoff");
	private static final List<String> OPERATIONS_TEAMS = List.of("operations team", "operations staff");
	private static final List<String> DEADLINE_CHECK_VERBS = List.of("confirm", "verify", "check");
	private static final List<String> DEADLINE_APPLICABILITY = List.of("remains applicable to", "still applies to");
	private static final List<String> PORT_NAMES = List.of("current shipment port", "current transshipment port");
	private static final Set<String> FORBIDDEN = Set.of("<", ">");

	private ScenarioMaritimeAiProse() { }

	static boolean matchesRisk(String statement, ShipmentRisk.Level severity,
			MaritimeAiClaim.RiskReview.SelectedWindowState state) {
		return matches(statement, riskAlternatives(severity, state));
	}

	static boolean matchesDeadline(String statement, String shipmentKey,
			MaritimeAiBusinessContext.Comparison comparison) {
		return matches(statement, deadlineAlternatives(shipmentKey, comparison));
	}

	static boolean matchesUncertainty(String statement) { return matches(statement, uncertaintyAlternatives()); }
	static boolean matchesEtaCheck(String statement) { return matches(statement, etaCheckAlternatives()); }
	static boolean matchesDeadlineCheck(String statement) { return matches(statement, deadlineCheckAlternatives()); }

	static List<String> riskAlternatives(ShipmentRisk.Level severity,
			MaritimeAiClaim.RiskReview.SelectedWindowState state) {
		if (severity == null || (state != MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE
				&& state != MaritimeAiClaim.RiskReview.SelectedWindowState.NO_REMAINING_WINDOW)) return List.of();
		String window = state == MaritimeAiClaim.RiskReview.SelectedWindowState.AVAILABLE
				? AVAILABLE_WINDOW_STATE : NO_REMAINING_WINDOW_STATE;
		String facts = "The backend %s %s connection risk. %s The AIS estimate is available at the scenario cutoff.";
		List<String> blocks = BACKEND_RISK_VERBS.stream().map(verb -> facts.formatted(verb,
				severity.name(), window)).toList();
		List<String> relevance = new ArrayList<>();
		for (String noun : REVISION_NOUNS) for (String verb : RISK_RELEVANCE_VERBS)
			for (String target : RISK_RELEVANCE_OBJECTS)
				relevance.add("The synthetic " + noun + " " + verb + " the " + target + ".");
		List<String> result = new ArrayList<>();
		for (String sentence : relevance) for (String block : blocks) {
			result.add(sentence + " " + block);
			result.add(block + " " + sentence);
		}
		return List.copyOf(result);
	}

	static List<String> deadlineAlternatives(String shipmentKey, MaritimeAiBusinessContext.Comparison comparison) {
		if (shipmentKey == null || !Set.of("A", "B").contains(shipmentKey) || comparison == null) return List.of();
		ScenarioDeadlineImpact impact = ScenarioDeadlineImpact.from(comparison);
		if (impact == null || ScenarioDeadlineImpact.expectedOutcome(impact) != comparison.outcome()) return List.of();
		List<String> outcomes = deadlineOutcomePhrases(impact);
		List<String> relevance = new ArrayList<>();
		for (String noun : REVISION_NOUNS) for (String verb : DEADLINE_RELEVANCE_VERBS)
			for (String target : DEADLINE_TARGETS)
				relevance.add("The synthetic " + noun + " " + verb
						+ " the arrival estimate against the recorded " + target + ".");
		List<String> result = new ArrayList<>();
		for (String comparisonVerb : COMPARISON_VERBS) for (String outcomeClause : outcomes)
			for (String shipmentQualifier : List.of("for Shipment " + shipmentKey + " ", "")) {
			String outcomeSentence = "The backend comparison " + shipmentQualifier + comparisonVerb + " "
					+ outcomeClause + ".";
			for (String relevanceSentence : relevance) {
				result.add(relevanceSentence + " " + outcomeSentence);
				result.add(outcomeSentence + " " + relevanceSentence);
			}
		}
		return List.copyOf(result);
	}

	private static List<String> deadlineOutcomePhrases(ScenarioDeadlineImpact impact) {
		List<String> result = new ArrayList<>();
		for (String arrival : arrivalPhrases()) {
			switch (impact.direction()) {
			case AFTER_DEADLINE -> {
				result.add(arrival + " exceeds the applicable transshipment-arrival deadline by " + impact.magnitude());
				result.add(arrival + " is " + impact.magnitude() + " later than the applicable transshipment-arrival deadline");
				result.add(arrival + " is " + impact.magnitude() + " beyond the applicable transshipment-arrival deadline");
				result.add(arrival + " exceeds the applicable transshipment-arrival deadline");
				result.add(arrival + " is after the applicable transshipment-arrival deadline");
			}
			case BEFORE_DEADLINE -> {
				result.add(arrival + " is " + impact.magnitude() + " before the applicable transshipment-arrival deadline");
				result.add(arrival + " is " + impact.magnitude() + " earlier than the applicable transshipment-arrival deadline");
				result.add(arrival + " is before the applicable transshipment-arrival deadline");
			}
			case AT_DEADLINE -> result.add(arrival + " is at the applicable transshipment-arrival deadline");
			}
		}
		return List.copyOf(result);
	}

	static List<String> uncertaintyAlternatives() {
		List<String> result = new ArrayList<>();
		for (String subject : UNCERTAINTY_SUBJECTS) for (String verb : UNCERTAINTY_VERBS)
			for (String object : UNCERTAINTY_OBJECTS)
				result.add("The synthetic " + subject + " " + verb + " " + object + ".");
		return List.copyOf(result);
	}

	static List<String> etaCheckAlternatives() {
		List<String> result = new ArrayList<>();
		for (String team : ETA_TEAMS) for (String verb : CHECK_VERBS) for (String target : ETA_TARGETS)
			for (String punctuation : List.of(".", "?")) result.add("Ask the " + team + " to " + verb + " " + target + punctuation);
		return List.copyOf(result);
	}

	static List<String> deadlineCheckAlternatives() {
		List<String> result = new ArrayList<>();
		for (String team : OPERATIONS_TEAMS) for (String verb : DEADLINE_CHECK_VERBS)
			for (String applicability : DEADLINE_APPLICABILITY) for (String port : PORT_NAMES)
				for (String punctuation : List.of(".", "?"))
					result.add("Ask the " + team + " to " + verb + " whether the transshipment-arrival deadline "
							+ applicability + " the " + port + punctuation);
		return List.copyOf(result);
	}

	/** The common prompt grammar and the matcher share every accepted choice. */
	static String promptInstructions() {
		return "Return exactly one complete sentence block for each risk and deadline statement, one uncertainty, and the two checks. "
				+ "Use only these complete patterns and their listed choices; this bounded English grammar supports the controlled demo, not general semantic equivalence. "
				+ "Risk relevance: 'The synthetic [" + choices(REVISION_NOUNS) + "] [" + choices(RISK_RELEVANCE_VERBS)
				+ "] the [" + choices(RISK_RELEVANCE_OBJECTS) + "].' Then state backend risk as 'The backend ["
				+ choices(BACKEND_RISK_VERBS) + "] {severity} connection risk.' followed by the window-state sentence and 'The AIS estimate is available at the scenario cutoff.' "
				+ "Use '" + AVAILABLE_WINDOW_STATE + "' for AVAILABLE and '" + NO_REMAINING_WINDOW_STATE
				+ "' for NO_REMAINING_WINDOW. "
				+ "The revision sentence and risk facts block may be in either order; keep the three risk-fact sentences together and in that order. "
				+ "Deadline relevance: 'The synthetic [" + choices(REVISION_NOUNS) + "] [" + choices(DEADLINE_RELEVANCE_VERBS)
				+ "] the arrival estimate against the recorded [" + choices(DEADLINE_TARGETS)
				+ "].' Prefer a second deadline sentence that names the supplied shipment key and reports the exact backend-provided direction and margin; its complete case-specific patterns are supplied separately. "
				+ "A concise alternative may omit the shipment name and numeric margin because this request is bound to one scenario shipment and the backend facts panel displays the exact comparison. It must still state the correct before/after direction; never omit or reverse that direction, or substitute a generic no-impact statement for an exceedance. The deadline revision sentence and comparison sentence may be in either order. Uncertainty: 'The synthetic ["
				+ choices(UNCERTAINTY_SUBJECTS) + "] [" + choices(UNCERTAINTY_VERBS) + "] ["
				+ choices(UNCERTAINTY_OBJECTS) + "].' ETA check: 'Ask the [" + choices(ETA_TEAMS) + "] to ["
				+ choices(CHECK_VERBS) + "] [" + choices(ETA_TARGETS) + "].' Deadline check: 'Ask the ["
				+ choices(OPERATIONS_TEAMS) + "] to [" + choices(DEADLINE_CHECK_VERBS)
				+ "] whether the transshipment-arrival deadline [" + choices(DEADLINE_APPLICABILITY) + "] the ["
				+ choices(PORT_NAMES) + "].' A final period or question mark is accepted for each check.";
	}

	static String deadlinePromptInstructions(String shipmentKey, MaritimeAiBusinessContext.Comparison comparison) {
		if (shipmentKey == null || !Set.of("A", "B").contains(shipmentKey) || comparison == null) return "";
		ScenarioDeadlineImpact impact = ScenarioDeadlineImpact.from(comparison);
		if (impact == null || ScenarioDeadlineImpact.expectedOutcome(impact) != comparison.outcome()) return "";
		String relationship = switch (impact.direction()) {
		case AFTER_DEADLINE -> "is " + impact.magnitude() + " after";
		case BEFORE_DEADLINE -> "is " + impact.magnitude() + " before";
		case AT_DEADLINE -> "is at";
		};
		return "For this request, backend comparison facts identify Shipment " + shipmentKey + "; its predicted arrival "
				+ relationship + " its applicable recorded deadline. Prefer naming this shipment and repeating the exact direction and margin without recalculating. "
				+ "A concise statement may omit the shipment name and numeric margin because this request is bound to one shipment and the backend facts panel displays the exact comparison. Always state the correct direction. "
				+ "The comparison sentence must begin 'The backend comparison [for Shipment " + shipmentKey + "] ["
				+ choices(COMPARISON_VERBS) + "]' and continue with exactly one of ["
				+ choices(deadlineOutcomePhrases(impact)) + "]. The statement must explain this shipment-specific consequence, not only repeat the shared risk category.";
	}

	static List<String> promptChoicePhrases(ScenarioDeadlineImpact impact) {
		List<String> choices = new ArrayList<>();
		choices.addAll(REVISION_NOUNS);
		choices.addAll(RISK_RELEVANCE_VERBS);
		choices.addAll(RISK_RELEVANCE_OBJECTS);
		choices.addAll(BACKEND_RISK_VERBS);
		choices.addAll(DEADLINE_RELEVANCE_VERBS);
		choices.addAll(DEADLINE_TARGETS);
		choices.addAll(COMPARISON_VERBS);
		if (impact != null) choices.addAll(deadlineOutcomePhrases(impact));
		choices.addAll(UNCERTAINTY_SUBJECTS);
		choices.addAll(UNCERTAINTY_VERBS);
		choices.addAll(UNCERTAINTY_OBJECTS);
		choices.addAll(ETA_TEAMS);
		choices.addAll(CHECK_VERBS);
		choices.addAll(ETA_TARGETS);
		choices.addAll(OPERATIONS_TEAMS);
		choices.addAll(DEADLINE_CHECK_VERBS);
		choices.addAll(DEADLINE_APPLICABILITY);
		choices.addAll(PORT_NAMES);
		return List.copyOf(choices);
	}

	private static List<String> arrivalPhrases() {
		List<String> result = new ArrayList<>();
		for (String article : ARRIVAL_ARTICLE_PREFIXES) for (String adjective : ARRIVAL_ADJECTIVES)
			result.add(article + adjective + " arrival");
		return List.copyOf(result);
	}

	private static boolean matches(String authored, List<String> alternatives) {
		String normalized = normalize(authored);
		return normalized != null && alternatives.stream().map(ScenarioMaritimeAiProse::normalize)
				.anyMatch(normalized::equals);
	}

	private static String normalize(String authored) {
		if (authored == null || authored.isBlank()) return null;
		if (authored.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
				|| Character.getType(codePoint) == Character.FORMAT)) return null;
		if (FORBIDDEN.stream().anyMatch(authored::contains)) return null;
		return authored.replace('\u2010', '-').replace('\u2011', '-').replace('\u2012', '-')
				.replace('\u2013', '-').replace('\u2014', '-').replace('\u2212', '-')
				.replaceAll("[\\t \\u00a0\\u1680\\u2000-\\u200a\\u202f\\u205f\\u3000]+", " ")
				.strip().toLowerCase(Locale.ROOT);
	}

	private static String choices(List<String> values) { return String.join(" / ", values); }
}
