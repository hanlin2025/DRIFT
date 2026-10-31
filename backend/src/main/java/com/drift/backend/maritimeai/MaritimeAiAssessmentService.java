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

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

@Service
public class MaritimeAiAssessmentService {
	private final ShipmentService shipments; private final ShipmentRepository shipmentRepository;
	private final MaritimeContextService context; private final MaritimeAiClient client; private final MaritimeAiAssessmentRepository assessments;
	private final ObjectMapper json; private final Clock clock; private final boolean enabled;
	private final boolean authenticRetrospectiveInferenceApproved; private final Set<String> approvedAuthenticSourceTypes;

	public MaritimeAiAssessmentService(ShipmentService shipments, ShipmentRepository shipmentRepository, MaritimeContextService context,
			MaritimeAiClient client, MaritimeAiAssessmentRepository assessments, ObjectMapper json, ObjectProvider<Clock> clocks,
			@Value("${app.maritime-ai.enabled:false}") boolean enabled,
			@Value("${app.maritime-ai.authentic-retrospective-inference-approved:false}") boolean authenticRetrospectiveInferenceApproved,
			@Value("${app.maritime-ai.authentic-retrospective-source-types-approved:}") String approvedAuthenticSourceTypes) {
		this.shipments = shipments; this.shipmentRepository = shipmentRepository; this.context = context; this.client = client;
		this.assessments = assessments; this.json = json; this.clock = clocks.getIfAvailable(Clock::systemUTC); this.enabled = enabled;
		this.authenticRetrospectiveInferenceApproved = authenticRetrospectiveInferenceApproved;
		this.approvedAuthenticSourceTypes = Arrays.stream(approvedAuthenticSourceTypes.split(",")).map(String::trim)
				.filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	@Transactional
	public MaritimeAiAssessmentResponse generate(AuthenticatedUser user, Long shipmentId) {
		ShipmentDetailResponse detail = shipments.get(user, shipmentId); // authorization gate and existing 401/403/404 semantics
		Instant now = clock.instant();
		MaritimeContextResponse bundle = context.forShipment(detail);
		MaritimeAiShipmentContext shipmentContext = new MaritimeAiShipmentContext(detail.transshipmentPort(),
				new MaritimeAiShipmentContext.Vessel("MOTHER", detail.motherVessel(), detail.motherVesselImo(), "PLANNED_ARRIVAL", detail.plannedMotherArrivalAt()),
				new MaritimeAiShipmentContext.Vessel("FEEDER", detail.feederVessel(), detail.feederVesselImo(), "PLANNED_DEPARTURE", detail.plannedFeederDepartureAt()));
		MaritimeAiEvidencePackage fullInput = new MaritimeAiEvidencePackage(bundle.contractVersion(), bundle.assessmentCutoffUtc(),
				bundle.shipmentAssociationConfidence(), shipmentContext, bundle.evidence());
		if (!enabled) return transientResponse(fullInput, MaritimeAiGenerationState.ABSTAINED, "FEATURE_DISABLED", null, "AI generation is disabled for this environment.");
		// Recorded replay, live, and unknown source modes never reach a provider. Authentic
		// retrospective sources require both a global approval and an explicit source-type allowlist.
		List<MaritimeEvidence> eligible = eligibleForInference(bundle.evidence(), authenticRetrospectiveInferenceApproved, approvedAuthenticSourceTypes);
		MaritimeAiEvidencePackage input = new MaritimeAiEvidencePackage(bundle.contractVersion(), bundle.assessmentCutoffUtc(),
				bundle.shipmentAssociationConfidence(), shipmentContext, eligible);
		if (input.evidence().isEmpty()) {
			return persist(detail, input, now, MaritimeAiClient.MaritimeAiClientResult.unavailable("No sufficient approved evidence."), MaritimeAiGenerationState.ABSTAINED, "INSUFFICIENT_EVIDENCE", null);
		}
		MaritimeAiClient.MaritimeAiClientResult result;
		try { result = client.interpret(input); }
		catch (RuntimeException ex) { result = MaritimeAiClient.MaritimeAiClientResult.unavailable("Model client failure: " + ex.getClass().getSimpleName()); }
		if (result.interpretation() == null) return persist(detail, input, now, result, MaritimeAiGenerationState.ABSTAINED, "MODEL_UNAVAILABLE", null);
		String validation = MaritimeAiResponseValidator.validate(result.interpretation(), input);
		if (validation != null) return persist(detail, input, now, result, MaritimeAiGenerationState.FAILED, validation, null);
		if (!result.interpretation().evidenceSufficient()) {
			MaritimeAiClient.MaritimeAiClientResult abstained = new MaritimeAiClient.MaritimeAiClientResult(result.provider(),
					result.model(), result.promptVersion(), null, "The model reported that the supplied evidence was insufficient.");
			return persist(detail, input, now, abstained, MaritimeAiGenerationState.ABSTAINED, "INSUFFICIENT_MODEL_EVIDENCE", null);
		}
		return persist(detail, input, now, result, MaritimeAiGenerationState.GENERATED, "VALID", result.interpretation());
	}

	static List<MaritimeEvidence> eligibleForInference(List<MaritimeEvidence> evidence) {
		return eligibleForInference(evidence, false, Set.of());
	}

	static List<MaritimeEvidence> eligibleForInference(List<MaritimeEvidence> evidence,
			boolean authenticRetrospectiveInferenceApproved, Set<String> approvedAuthenticSourceTypes) {
		return evidence.stream()
				.filter(item -> item.evidenceSufficient() && !"INCOMPATIBLE".equals(item.compatibilityState())
						&& !"STALE".equals(item.freshness()))
				.filter(item -> "SYNTHETIC_DEMONSTRATION".equals(item.sourceMode())
						|| (authenticRetrospectiveInferenceApproved && "RETROSPECTIVE_ANALYSIS".equals(item.sourceMode())
								&& approvedAuthenticSourceTypes.contains(item.sourceType())))
				.toList();
	}

	@Transactional(readOnly = true)
	public List<MaritimeAiAssessmentResponse> list(AuthenticatedUser user, Long shipmentId) {
		shipments.get(user, shipmentId); // do not expose assessments across company/importer visibility boundaries
		return assessments.findByShipmentIdOrderByCreatedAtDesc(shipmentId).stream().map(this::response).toList();
	}

	private MaritimeAiAssessmentResponse persist(ShipmentDetailResponse detail, MaritimeAiEvidencePackage input, Instant now,
			MaritimeAiClient.MaritimeAiClientResult result, MaritimeAiGenerationState state, String validation, MaritimeAiInterpretation output) {
		Shipment shipment = shipmentRepository.findById(detail.id()).orElseThrow();
		MaritimeAiDeterministicSummary summary = deterministicSummary(input);
		String outputJson = write(new MaritimeAiStoredAssessmentContent(output, summary));
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
		return new MaritimeAiDeterministicSummary("cdg247.deterministic-facts.v2.canonical-references", "Backend-owned evidence and shipment-schedule facts; not AI reasoning.",
				List.copyOf(schedule), evidence, limitations);
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
			return new MaritimeAiStoredAssessmentContent(json.readValue(value, MaritimeAiInterpretation.class), null);
		} catch (Exception ex) { throw new IllegalStateException("Stored AI output is invalid", ex); }
	}
	private String hash(MaritimeAiEvidencePackage value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)); return java.util.HexFormat.of().formatHex(digest); } catch (Exception ex) { throw new IllegalStateException(ex); } }
}
