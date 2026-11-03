package com.drift.backend.maritimeai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Arrays;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.maritimecontext.MaritimeContextResponse;
import com.drift.backend.maritimecontext.MaritimeContextService;
import com.drift.backend.shipment.Shipment;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentRepository;
import com.drift.backend.shipment.ShipmentService;
import com.drift.backend.shipment.ShipmentViewAuthorization;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
public class MaritimeAiAssessmentService {
	private final ShipmentService shipments; private final ShipmentRepository shipmentRepository;
	private final MaritimeContextService context; private final MaritimeAiClient client; private final MaritimeAiAssessmentRepository assessments;
	private final MaritimeAiAssessmentHistoryAccessPolicy historyAccess;
	private final ObjectMapper json; private final Clock clock; private final boolean enabled;
	private final boolean authenticRetrospectiveInferenceApproved; private final Set<String> approvedAuthenticSourceTypes;
	private final Set<String> approvedAuthenticEvidenceIds;

	public MaritimeAiAssessmentService(ShipmentService shipments, ShipmentRepository shipmentRepository, MaritimeContextService context,
			MaritimeAiClient client, MaritimeAiAssessmentRepository assessments, MaritimeAiAssessmentHistoryAccessPolicy historyAccess,
			ObjectMapper json, ObjectProvider<Clock> clocks,
			@Value("${app.maritime-ai.enabled:false}") boolean enabled,
			@Value("${app.maritime-ai.authentic-retrospective-inference-approved:false}") boolean authenticRetrospectiveInferenceApproved,
			@Value("${app.maritime-ai.authentic-retrospective-source-types-approved:}") String approvedAuthenticSourceTypes,
			@Value("${app.maritime-ai.authentic-retrospective-evidence-ids-approved:}") String approvedAuthenticEvidenceIds) {
		this.shipments = shipments; this.shipmentRepository = shipmentRepository; this.context = context; this.client = client;
		this.assessments = assessments; this.historyAccess = historyAccess; this.json = json;
		this.clock = clocks.getIfAvailable(Clock::systemUTC); this.enabled = enabled;
		this.authenticRetrospectiveInferenceApproved = authenticRetrospectiveInferenceApproved;
		this.approvedAuthenticSourceTypes = Arrays.stream(approvedAuthenticSourceTypes.split(",")).map(String::trim)
				.filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
		this.approvedAuthenticEvidenceIds = Arrays.stream(approvedAuthenticEvidenceIds.split(",")).map(String::trim)
				.filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	@Transactional
	public MaritimeAiAssessmentResponse generate(AuthenticatedUser user, Long shipmentId) {
		ShipmentDetailResponse detail = shipments.get(user, shipmentId); // authorization gate and existing 401/403/404 semantics
		Instant now = clock.instant();
		MaritimeContextResponse bundle = context.forShipment(detail);
		MaritimeAiShipmentContext shipmentContext = new MaritimeAiShipmentContext(detail.transshipmentPort(),
				new MaritimeAiShipmentContext.Vessel("MOTHER", detail.motherVessel(), detail.motherVesselImo(), "PLANNED_ARRIVAL", detail.plannedMotherArrivalAt()),
				new MaritimeAiShipmentContext.Vessel("FEEDER", detail.feederVessel(), detail.feederVesselImo(), "PLANNED_DEPARTURE", detail.plannedFeederDepartureAt()),
				MaritimeAiConnectionRiskContext.from(detail), MaritimeAiBusinessContext.from(detail));
		MaritimeAiEvidencePackage fullInput = new MaritimeAiEvidencePackage(bundle.contractVersion(), bundle.assessmentCutoffUtc(),
				bundle.shipmentAssociationConfidence(), shipmentContext, bundle.evidence());
		if (!enabled) return recheckShipmentAccess(user, shipmentId,
				transientResponse(fullInput, MaritimeAiGenerationState.ABSTAINED, "FEATURE_DISABLED", null,
						"AI generation is disabled for this environment."));
		// Recorded replay, live, and unknown source modes never reach a provider. Authentic
		// retrospective sources require global, source-type, and exact curated-record approvals.
		if (hasUnapprovedAuthenticEvidence(bundle.evidence(), authenticRetrospectiveInferenceApproved,
				approvedAuthenticSourceTypes, approvedAuthenticEvidenceIds)) {
			MaritimeAiEvidencePackage blocked = new MaritimeAiEvidencePackage(bundle.contractVersion(), bundle.assessmentCutoffUtc(),
					bundle.shipmentAssociationConfidence(), shipmentContext, List.of());
			return recheckShipmentAccess(user, shipmentId, persist(detail, blocked, now, MaritimeAiClient.MaritimeAiClientResult.unavailable(
					"An authentic evidence record is not approved for external processing."),
				MaritimeAiGenerationState.ABSTAINED, "UNAPPROVED_AUTHENTIC_EVIDENCE", null));
		}
		List<MaritimeEvidence> eligible = eligibleForInference(bundle.evidence(), authenticRetrospectiveInferenceApproved,
				approvedAuthenticSourceTypes, approvedAuthenticEvidenceIds);
		MaritimeAiEvidencePackage input = new MaritimeAiEvidencePackage(bundle.contractVersion(), bundle.assessmentCutoffUtc(),
				bundle.shipmentAssociationConfidence(), shipmentContext, eligible);
		if (input.evidence().isEmpty()) {
			return recheckShipmentAccess(user, shipmentId, persist(detail, input, now,
					MaritimeAiClient.MaritimeAiClientResult.unavailable("No sufficient approved evidence."),
					MaritimeAiGenerationState.ABSTAINED, "INSUFFICIENT_EVIDENCE", null));
		}
		MaritimeAiClient.MaritimeAiClientResult result;
		try { result = client.interpret(input); }
		catch (RuntimeException ex) { result = MaritimeAiClient.MaritimeAiClientResult.unavailable("Model client failure: " + ex.getClass().getSimpleName()); }
		if (result.interpretation() == null) return recheckShipmentAccess(user, shipmentId,
				persist(detail, input, now, result, MaritimeAiGenerationState.ABSTAINED, "MODEL_UNAVAILABLE", null));
		String validation = MaritimeAiResponseValidator.validate(result.interpretation(), input);
		if (validation != null) return recheckShipmentAccess(user, shipmentId,
				persist(detail, input, now, result, MaritimeAiGenerationState.FAILED, validation, null));
		if (!result.interpretation().evidenceSufficient()) {
			MaritimeAiClient.MaritimeAiClientResult abstained = new MaritimeAiClient.MaritimeAiClientResult(result.provider(),
					result.model(), result.promptVersion(), null, "The model reported that the supplied evidence was insufficient.");
			return recheckShipmentAccess(user, shipmentId,
					persist(detail, input, now, abstained, MaritimeAiGenerationState.ABSTAINED, "INSUFFICIENT_MODEL_EVIDENCE", null));
		}
		return recheckShipmentAccess(user, shipmentId,
				persist(detail, input, now, result, MaritimeAiGenerationState.GENERATED, "VALID", result.interpretation()));
	}

	/**
	 * Generates a transient interpretation only for an explicitly enabled controlled-scenario
	 * request. This path never calls the ordinary shipment reader and never persists a row.
	 */
	MaritimeAiAssessmentResponse generateScenarioTransient(MaritimeAiEvidencePackage input, boolean scenarioEnabled) {
		if (!scenarioEnabled) return scenarioTransient(input, MaritimeAiGenerationState.ABSTAINED,
				"SCENARIO_AI_DISABLED", null, "Scenario AI interpretation is disabled for this environment.");
		if (!enabled) return scenarioTransient(input, MaritimeAiGenerationState.ABSTAINED,
				"FEATURE_DISABLED", null, "AI generation is disabled for this environment.");
		if (!(client instanceof ConfiguredMaritimeAiClient)) return scenarioTransient(input,
				MaritimeAiGenerationState.ABSTAINED, "REAL_PROVIDER_REQUIRED", null,
				"A configured real inference provider is required for scenario interpretation.");
		if (input.evidence().stream().anyMatch(item -> !"SYNTHETIC_DEMONSTRATION".equals(item.sourceMode()))) {
			return scenarioTransient(input, MaritimeAiGenerationState.ABSTAINED,
					"SYNTHETIC_EVIDENCE_REQUIRED", null, "Only synthetic scenario evidence can be sent for this interpretation.");
		}
		List<MaritimeEvidence> eligible = eligibleForInference(input.evidence(), false, Set.of(), Set.of());
		MaritimeAiEvidencePackage eligibleInput = new MaritimeAiEvidencePackage(input.bundleVersion(),
				input.assessmentCutoffUtc(), input.shipmentAssociationConfidence(), input.shipmentContext(), eligible);
		if (eligible.isEmpty()) return scenarioTransient(eligibleInput, MaritimeAiGenerationState.ABSTAINED,
				"INSUFFICIENT_EVIDENCE", null, "No sufficient, current synthetic evidence is available for inference.");
		MaritimeAiClient.MaritimeAiClientResult result;
		try { result = client.interpret(eligibleInput); }
		catch (RuntimeException ex) {
			result = MaritimeAiClient.MaritimeAiClientResult.unavailable("Model client failure: " + ex.getClass().getSimpleName());
		}
		if (result.interpretation() == null) return scenarioTransient(eligibleInput,
				MaritimeAiGenerationState.ABSTAINED, "MODEL_UNAVAILABLE", result,
				result.failure() == null ? "The configured provider returned no interpretation." : result.failure());
		String validation = MaritimeAiResponseValidator.validate(result.interpretation(), eligibleInput);
		if (validation != null) return scenarioTransient(eligibleInput, MaritimeAiGenerationState.FAILED,
				validation, result, "The provider response failed validation (" + validation + "). No interpretation is displayed.");
		if (!result.interpretation().evidenceSufficient()) return scenarioTransient(eligibleInput,
				MaritimeAiGenerationState.ABSTAINED, "INSUFFICIENT_MODEL_EVIDENCE", result,
				"The model reported that the supplied evidence was insufficient.");
		return scenarioTransient(eligibleInput, MaritimeAiGenerationState.GENERATED, "VALID", result, null);
	}

	private MaritimeAiAssessmentResponse scenarioTransient(MaritimeAiEvidencePackage input,
			MaritimeAiGenerationState state, String validation, MaritimeAiClient.MaritimeAiClientResult result,
			String limitation) {
		String provider = result == null || result.provider() == null ? "UNCONFIGURED" : result.provider();
		String model = result == null || result.model() == null ? "" : result.model();
		String prompt = result == null || result.promptVersion() == null
				? ConfiguredMaritimeAiClient.PROMPT_VERSION : result.promptVersion();
		MaritimeAiInterpretation output = state == MaritimeAiGenerationState.GENERATED ? result.interpretation() : null;
		return new MaritimeAiAssessmentResponse(null, state.name(), validation, input.bundleVersion(), hash(input),
				input.assessmentCutoffUtc(), provider, model, prompt, clock.instant().toString(), output,
				limitation, deterministicSummary(input));
	}

	private MaritimeAiAssessmentResponse recheckShipmentAccess(AuthenticatedUser user, Long shipmentId,
			MaritimeAiAssessmentResponse response) {
		shipments.authorizeView(user, shipmentId);
		return response;
	}

	static List<MaritimeEvidence> eligibleForInference(List<MaritimeEvidence> evidence) {
		return eligibleForInference(evidence, false, Set.of());
	}

	static List<MaritimeEvidence> eligibleForInference(List<MaritimeEvidence> evidence,
			boolean authenticRetrospectiveInferenceApproved, Set<String> approvedAuthenticSourceTypes) {
		return eligibleForInference(evidence, authenticRetrospectiveInferenceApproved, approvedAuthenticSourceTypes, Set.of());
	}

	/**
	 * Authentic records require a global organisational approval, a source-type approval, and an
	 * exact curated-record approval. The last condition prevents a newly exported record from
	 * being transmitted merely because another record from the same source type was approved.
	 */
	static List<MaritimeEvidence> eligibleForInference(List<MaritimeEvidence> evidence,
			boolean authenticRetrospectiveInferenceApproved, Set<String> approvedAuthenticSourceTypes,
			Set<String> approvedAuthenticEvidenceIds) {
		return evidence.stream()
				.filter(item -> item.evidenceSufficient() && !"INCOMPATIBLE".equals(item.compatibilityState())
						&& !"STALE".equals(item.freshness()))
				.filter(item -> "SYNTHETIC_DEMONSTRATION".equals(item.sourceMode())
						|| (authenticRetrospectiveInferenceApproved && "RETROSPECTIVE_ANALYSIS".equals(item.sourceMode())
								&& approvedAuthenticSourceTypes.contains(item.sourceType())
								&& approvedAuthenticEvidenceIds.contains(item.evidenceId())))
				.toList();
	}

	/**
	 * A mixed authentic bundle is not safe to trim silently: omitting an unapproved record could
	 * change the meaning of the approved record. Abstain before invoking any provider instead.
	 */
	static boolean hasUnapprovedAuthenticEvidence(List<MaritimeEvidence> evidence,
			boolean authenticRetrospectiveInferenceApproved, Set<String> approvedAuthenticSourceTypes,
			Set<String> approvedAuthenticEvidenceIds) {
		return evidence.stream()
				.filter(item -> item.evidenceSufficient() && !"INCOMPATIBLE".equals(item.compatibilityState())
						&& !"STALE".equals(item.freshness()) && "RETROSPECTIVE_ANALYSIS".equals(item.sourceMode()))
				.anyMatch(item -> !authenticRetrospectiveInferenceApproved
						|| !approvedAuthenticSourceTypes.contains(item.sourceType())
						|| !approvedAuthenticEvidenceIds.contains(item.evidenceId()));
	}

	// History authorization takes a PostgreSQL row lock so importer reassignment serializes after the read.
	@Transactional
	public List<MaritimeAiAssessmentResponse> list(AuthenticatedUser user, Long shipmentId) {
		ShipmentViewAuthorization authorization = shipments.authorizeView(user, shipmentId);
		return assessments.findByShipmentIdOrderByCreatedAtDesc(shipmentId).stream()
				.filter(value -> historyAccess.canRead(value, authorization, historyScope(value.getOutputJson())))
				.map(this::response).toList();
	}

	private MaritimeAiAssessmentResponse persist(ShipmentDetailResponse detail, MaritimeAiEvidencePackage input, Instant now,
			MaritimeAiClient.MaritimeAiClientResult result, MaritimeAiGenerationState state, String validation, MaritimeAiInterpretation output) {
		Shipment shipment = shipmentRepository.findById(detail.id()).orElseThrow();
		MaritimeAiDeterministicSummary summary = deterministicSummary(input);
		Long importerCompanyId = detail.importer() == null ? null : detail.importer().id();
		String outputJson = write(new MaritimeAiStoredAssessmentContent(output, summary,
				MaritimeAiAssessmentHistoryScope.captured(importerCompanyId)));
		String failure = output == null ? result.failure() : null;
		MaritimeAiAssessment saved = assessments.save(new MaritimeAiAssessment(shipment, UUID.randomUUID().toString(), input.bundleVersion(), hash(input),
				Instant.parse(input.assessmentCutoffUtc()), result.provider(), result.model(), result.promptVersion(), state, validation, outputJson, failure, now));
		return response(saved);
	}

	private MaritimeAiAssessmentResponse transientResponse(MaritimeAiEvidencePackage input, MaritimeAiGenerationState state, String validation,
			MaritimeAiInterpretation output, String limitation) {
		return new MaritimeAiAssessmentResponse(null, state.name(), validation, input.bundleVersion(), hash(input), input.assessmentCutoffUtc(),
				"UNCONFIGURED", "", ConfiguredMaritimeAiClient.PROMPT_VERSION, null, output, limitation, deterministicSummary(input));
	}
	private MaritimeAiAssessmentResponse response(MaritimeAiAssessment value) {
		MaritimeAiStoredAssessmentContent content = value.getOutputJson() == null ? null : readContent(value.getOutputJson());
		MaritimeAiInterpretation output = content == null ? null : content.interpretation();
		return new MaritimeAiAssessmentResponse(value.getAssessmentId(), value.getGenerationState().name(), value.getValidationStatus(),
				value.getBundleVersion(), value.getBundleHash(), value.getAssessmentCutoffUtc().toString(), value.getProvider(), value.getModel(), value.getPromptVersion(),
				value.getCreatedAt().toString(), output, value.getFailureMetadata(), content == null ? null : content.deterministicSummary());
	}
	private MaritimeAiAssessmentHistoryScope historyScope(String outputJson) {
		if (outputJson == null) return null;
		try {
			JsonNode root = json.readTree(outputJson);
			JsonNode scope = root == null ? null : root.get("historyScope");
			JsonNode version = scope == null ? null : scope.get("version");
			if (scope == null || !scope.isObject() || version == null || !version.isIntegralNumber()
					|| !version.canConvertToInt()) return null;
			JsonNode importer = scope.get("importerCompanyId");
			Long importerCompanyId = null;
			if (importer != null && !importer.isNull()) {
				if (!importer.isIntegralNumber() || !importer.canConvertToLong() || importer.longValue() < 1) return null;
				importerCompanyId = importer.longValue();
			}
			return new MaritimeAiAssessmentHistoryScope(version.intValue(), importerCompanyId);
		} catch (Exception ex) {
			// Unknown, legacy, or malformed provenance is hidden from importers without altering row data.
			return null;
		}
	}
	private MaritimeAiDeterministicSummary deterministicSummary(MaritimeAiEvidencePackage input) {
		List<MaritimeAiDeterministicSummary.Fact> schedule = new java.util.ArrayList<>();
		if (input.shipmentContext() != null) {
			addScheduleFact(schedule, input.shipmentContext().motherVessel());
			addScheduleFact(schedule, input.shipmentContext().feederVessel());
		}
		List<MaritimeAiDeterministicSummary.Fact> evidence = input.evidence().stream().map(item ->
				new MaritimeAiDeterministicSummary.Fact("evidence:" + item.evidenceId(), "RETRIEVED_EVIDENCE", item.vesselIdentity(), item.observationType(), item.sourceEventAt(),
						item.previousValue(), item.currentValue(), item.evidenceId(), item.sourceType(), item.sourceMode(), item.associationScope(), item.citation(),
						item.observedAt(), item.retrievedAtUtc())).toList();
		List<String> limitations = input.evidence().stream().map(MaritimeEvidence::limitation).filter(java.util.Objects::nonNull).distinct().toList();
		MaritimeAiConnectionRiskContext risk = input.shipmentContext() == null ? null : input.shipmentContext().connectionRisk();
		MaritimeAiBusinessContext business = input.shipmentContext() == null ? null : input.shipmentContext().businessContext();
		return new MaritimeAiDeterministicSummary("cdg247.deterministic-facts.v4.business-risk-snapshots", "Backend-owned evidence, shipment-schedule, risk, and importer-constraint facts; not AI reasoning.",
				List.copyOf(schedule), evidence, limitations,
				MaritimeAiDeterministicSummary.RiskSnapshot.from(risk, MaritimeAiCanonicalFactReferences.riskReferences(input)),
				MaritimeAiDeterministicSummary.BusinessSnapshot.from(business,
						MaritimeAiCanonicalFactReferences.businessReferences(input)));
	}
	private static void addScheduleFact(List<MaritimeAiDeterministicSummary.Fact> schedule, MaritimeAiShipmentContext.Vessel vessel) {
		if (vessel == null || vessel.plannedTime() == null) return;
		schedule.add(new MaritimeAiDeterministicSummary.Fact("schedule:" + vessel.role() + ":" + vessel.plannedMovementType(), "SHIPMENT_SCHEDULE", vessel.name(), vessel.plannedMovementType(),
				vessel.plannedTime().toString(), null, null, null, "SHIPMENT_PLAN", "DETERMINISTIC_CONTEXT", "SCHEDULE_CONTEXT",
				"Shipment planning context; not an independently retrieved vessel movement.", null, null));
	}
	private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("Could not serialize AI output", ex); } }
	private MaritimeAiStoredAssessmentContent readContent(String value) {
		try {
			JsonNode tree = json.readTree(value);
			if (tree.has("interpretation") || tree.has("deterministicSummary")) return json.readValue(value, MaritimeAiStoredAssessmentContent.class);
			return new MaritimeAiStoredAssessmentContent(json.readValue(value, MaritimeAiInterpretation.class), null, null);
		} catch (Exception ex) { throw new IllegalStateException("Stored AI output is invalid", ex); }
	}
	private String hash(MaritimeAiEvidencePackage value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)); return java.util.HexFormat.of().formatHex(digest); } catch (Exception ex) { throw new IllegalStateException(ex); } }
}
