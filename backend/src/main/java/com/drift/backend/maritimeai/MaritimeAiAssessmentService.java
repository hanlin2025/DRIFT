package com.drift.backend.maritimeai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
import com.drift.backend.shipment.ControlledScenarioSessionResponse;
import com.drift.backend.shipment.ControlledScenarioSnapshot;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

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

	/** Compatibility adapter for callers that need a transient scenario response. The authenticated scenario endpoint
	 * uses {@link #generateScenario(MaritimeAiEvidencePackage, boolean)} and persists its result separately.
	 */
	MaritimeAiAssessmentResponse generateScenarioTransient(MaritimeAiEvidencePackage input, boolean scenarioEnabled) {
		return scenarioTransient(input, generateScenario(input, scenarioEnabled));
	}

	MaritimeAiScenarioGenerationResult generateScenario(MaritimeAiEvidencePackage input, boolean scenarioEnabled) {
		if (!scenarioEnabled) return scenarioResult(MaritimeAiGenerationState.ABSTAINED, "SCENARIO_AI_DISABLED", null,
				"Scenario AI interpretation is disabled for this environment.");
		if (!enabled) return scenarioResult(MaritimeAiGenerationState.ABSTAINED, "FEATURE_DISABLED", null,
				"AI generation is disabled for this environment.");
		if (input.shipmentContext() != null && input.shipmentContext().scenario() != null
				&& "BASELINE".equals(input.shipmentContext().scenario().stage())) {
			return scenarioResult(MaritimeAiGenerationState.ABSTAINED, "INSUFFICIENT_EVIDENCE",
					MaritimeAiClient.MaritimeAiClientResult.unavailable("BASELINE has no model interpretation."),
					"The baseline snapshot intentionally has no AI interpretation.");
		}
		if (!(client instanceof ConfiguredMaritimeAiClient)) return scenarioResult(MaritimeAiGenerationState.ABSTAINED,
				"REAL_PROVIDER_REQUIRED", null, "A configured real inference provider is required for scenario interpretation.");
		if (input.evidence().stream().anyMatch(item -> !"SYNTHETIC_DEMONSTRATION".equals(item.sourceMode()))) {
			return scenarioResult(MaritimeAiGenerationState.ABSTAINED, "SYNTHETIC_EVIDENCE_REQUIRED", null,
					"Only synthetic scenario evidence can be sent for this interpretation.");
		}
		List<MaritimeEvidence> eligible = eligibleForInference(input.evidence(), false, Set.of(), Set.of());
		MaritimeAiEvidencePackage eligibleInput = new MaritimeAiEvidencePackage(input.bundleVersion(),
				input.assessmentCutoffUtc(), input.shipmentAssociationConfidence(), input.shipmentContext(), eligible);
		if (eligible.isEmpty()) return scenarioResult(MaritimeAiGenerationState.ABSTAINED,
				"INSUFFICIENT_EVIDENCE", MaritimeAiClient.MaritimeAiClientResult.unavailable("No sufficient approved evidence."),
				"No sufficient, current synthetic evidence is available for inference.");
		boolean scenarioV16 = client instanceof ConfiguredMaritimeAiClient configured
				&& configured.usesScenarioV16(eligibleInput);
		if (scenarioV16) {
			String missingField = ScenarioMaritimeAiOutput.preflightFailureField(eligibleInput);
			if (missingField != null) {
				var unavailable = new MaritimeAiClient.MaritimeAiClientResult("UNCONFIGURED", "",
						ConfiguredMaritimeAiClient.OPENAI_SCENARIO_PROMPT_VERSION, null,
						"The controlled snapshot does not contain complete fresh scenario context.", null,
						"preflight", null);
				return scenarioResult(MaritimeAiGenerationState.ABSTAINED, "INSUFFICIENT_SCENARIO_CONTEXT",
						unavailable, "The controlled snapshot lacks complete, current risk, deadline, or revision facts.");
			}
		}
		MaritimeAiClient.MaritimeAiClientResult result;
		try { result = client.interpret(eligibleInput); }
		catch (RuntimeException ex) {
			result = MaritimeAiClient.MaritimeAiClientResult.unavailable("Model client failure: " + ex.getClass().getSimpleName());
		}
		if (scenarioV16) {
			if (result.scenarioFailureCode() != null) {
				String failureCode = result.scenarioFailureCode();
				return scenarioResult(MaritimeAiGenerationState.FAILED, failureCode, result,
						"The provider response failed validation (" + failureCode + " at output). No interpretation is displayed.");
			}
			if (result.failure() != null || result.scenarioOutputText() == null) return scenarioResult(
					MaritimeAiGenerationState.ABSTAINED, "MODEL_UNAVAILABLE", result,
					result.failure() == null ? "The configured provider returned no interpretation." : result.failure());
			MaritimeAiResponseValidator.ScenarioValidation validation =
					MaritimeAiResponseValidator.validateScenarioV16(result.scenarioOutputText(), eligibleInput);
			if (validation.abstained()) return scenarioResult(MaritimeAiGenerationState.ABSTAINED,
					validation.validationStatus(), result,
					"The provider did not supply enough grounded evidence for a complete scenario interpretation.");
			if (!validation.valid()) return scenarioResult(MaritimeAiGenerationState.FAILED,
					validation.validationStatus(), result, "The provider response failed validation ("
							+ validation.validationStatus() + " at " + validation.fieldPath() + "). No interpretation is displayed.");
			MaritimeAiClient.MaritimeAiClientResult projected = new MaritimeAiClient.MaritimeAiClientResult(result.provider(),
					result.model(), result.promptVersion(), validation.interpretation(), null, result.scenarioOutputText(),
					result.scenarioEnvelopeStatus(), null);
			return new MaritimeAiScenarioGenerationResult(projected, MaritimeAiGenerationState.GENERATED,
					"VALID", validation.interpretation(), null);
		}
		if (result.interpretation() == null) return scenarioResult(MaritimeAiGenerationState.ABSTAINED,
				"MODEL_UNAVAILABLE", result,
				result.failure() == null ? "The configured provider returned no interpretation." : result.failure());
		String validation = MaritimeAiResponseValidator.validate(result.interpretation(), eligibleInput);
		if (validation != null) {
			String diagnostic = MaritimeAiResponseValidator.safeValidationDiagnostic(validation, result.interpretation(), eligibleInput);
			return scenarioResult(MaritimeAiGenerationState.FAILED, validation, result,
					"The provider response failed validation (" + diagnostic + "). No interpretation is displayed.");
		}
		if (!result.interpretation().evidenceSufficient()) return scenarioResult(MaritimeAiGenerationState.ABSTAINED,
				"INSUFFICIENT_MODEL_EVIDENCE", result, "The model reported that the supplied evidence was insufficient.");
		return new MaritimeAiScenarioGenerationResult(result, MaritimeAiGenerationState.GENERATED, "VALID",
				result.interpretation(), null);
	}

	private static MaritimeAiScenarioGenerationResult scenarioResult(MaritimeAiGenerationState state, String validation,
			MaritimeAiClient.MaritimeAiClientResult result, String limitation) {
		MaritimeAiClient.MaritimeAiClientResult clientResult = result == null
				? MaritimeAiClient.MaritimeAiClientResult.unavailable(limitation) : result;
		return new MaritimeAiScenarioGenerationResult(clientResult, state, validation, null, limitation);
	}

	private MaritimeAiAssessmentResponse scenarioTransient(MaritimeAiEvidencePackage input, MaritimeAiScenarioGenerationResult result) {
		MaritimeAiClient.MaritimeAiClientResult clientResult = result.clientResult();
		String provider = clientResult.provider() == null ? "UNCONFIGURED" : clientResult.provider();
		String model = clientResult.model() == null ? "" : clientResult.model();
		String prompt = clientResult.promptVersion() == null
				? ConfiguredMaritimeAiClient.PROMPT_VERSION : clientResult.promptVersion();
		return new MaritimeAiAssessmentResponse(null, result.state().name(), result.validationStatus(), input.bundleVersion(), hash(input),
				input.assessmentCutoffUtc(), provider, model, prompt, clock.instant().toString(), result.interpretation(),
				result.limitation(), deterministicSummary(input));
	}

	@Transactional
	public ShipmentViewAuthorization captureScenarioAuthorization(AuthenticatedUser user, Long shipmentId) {
		return shipments.authorizeView(user, shipmentId);
	}

	MaritimeAiScenarioAssessmentContext scenarioContext(AuthenticatedUser user, ControlledScenarioSessionResponse session,
			ControlledScenarioSnapshot snapshot, ControlledScenarioSnapshot.ShipmentRead row, Long shipmentId,
			ShipmentViewAuthorization authorization, MaritimeAiEvidencePackage input) {
		String evidenceHash = hash(input.evidence());
		String bundleHash = hash(input);
		MaritimeAiScenarioAssessmentContext material = new MaritimeAiScenarioAssessmentContext(
				MaritimeAiScenarioAssessmentContext.CURRENT_VERSION, session.scenarioId(), session.scenarioContractVersion(),
				session.sessionId(), user.id(), snapshot.epochId(), snapshot.revision(), snapshot.snapshotId(),
				snapshot.simulationCutoffUtc().toString(), row.scenarioShipmentKey(), row.scenarioShipmentReference(),
				snapshot.stage().name(), shipmentId, authorization.viewerCompanyId(), authorization.managingCompanyId(),
				authorization.importerCompanyId(), evidenceHash, bundleHash, null);
		return new MaritimeAiScenarioAssessmentContext(material.schemaVersion(), material.scenarioId(), material.scenarioVersion(),
				material.sessionId(), material.userId(), material.epochId(), material.revision(), material.snapshotId(),
				material.simulationCutoffUtc(), material.scenarioShipmentKey(), material.scenarioShipmentReference(),
				material.stage(), material.shipmentId(), material.viewerCompanyId(), material.managingCompanyId(),
				material.importerCompanyId(), material.evidenceHash(), material.bundleHash(), hash(material.hashMaterial()));
	}

	@Transactional
	public MaritimeAiAssessmentResponse persistScenario(AuthenticatedUser user, Long shipmentId,
			MaritimeAiEvidencePackage input, MaritimeAiScenarioAssessmentContext scenarioContext,
			ShipmentViewAuthorization capturedAuthorization, MaritimeAiScenarioGenerationResult result) {
		ShipmentViewAuthorization currentAuthorization = shipments.authorizeView(user, shipmentId);
		if (!sameAuthorization(capturedAuthorization, currentAuthorization)
				|| !Objects.equals(scenarioContext.viewerCompanyId(), currentAuthorization.viewerCompanyId())
				|| !Objects.equals(scenarioContext.managingCompanyId(), currentAuthorization.managingCompanyId())
				|| !Objects.equals(scenarioContext.importerCompanyId(), currentAuthorization.importerCompanyId())
				|| !Objects.equals(scenarioContext.userId(), user.id())
				|| !Objects.equals(scenarioContext.shipmentId(), shipmentId)
				|| !scenarioContext.hasValidShape()
				|| !Objects.equals(scenarioContext.evidenceHash(), hash(input.evidence()))
				|| !Objects.equals(scenarioContext.bundleHash(), hash(input))
				|| !Objects.equals(scenarioContext.contextHash(), hash(scenarioContext.hashMaterial()))) {
			throw new ShipmentDetailForbiddenException();
		}
		Shipment shipment = shipmentRepository.findById(shipmentId).orElseThrow(ShipmentNotFoundException::new);
		if (!Objects.equals(shipment.getCompany().getId(), currentAuthorization.managingCompanyId())) {
			throw new ShipmentDetailForbiddenException();
		}
		MaritimeAiAssessmentHistoryScope historyScope = MaritimeAiAssessmentHistoryScope.captured(currentAuthorization.importerCompanyId());
		String outputJson = write(new MaritimeAiStoredAssessmentContent(result.interpretation(), deterministicSummary(input),
				historyScope, MaritimeAiStoredAssessmentContent.CONTROLLED_SCENARIO_ASSESSMENT, scenarioContext));
		String failure = result.interpretation() == null
				? firstNonBlank(result.clientResult().failure(), result.limitation()) : null;
		MaritimeAiAssessment saved = assessments.save(new MaritimeAiAssessment(shipment, UUID.randomUUID().toString(), input.bundleVersion(),
				hash(input), Instant.parse(input.assessmentCutoffUtc()), result.clientResult().provider(), result.clientResult().model(),
				result.clientResult().promptVersion(), result.state(), result.validationStatus(),
				MaritimeAiStoredAssessmentContent.CONTROLLED_SCENARIO_ASSESSMENT, outputJson, failure, clock.instant()));
		return response(saved);
	}

	@Transactional(readOnly = true)
	public Optional<MaritimeAiScenarioAssessmentContext> scenarioContext(String assessmentId) {
		if (assessmentId == null || assessmentId.length() != 36) return Optional.empty();
		return assessments.findByAssessmentId(assessmentId).flatMap(this::storedScenarioContext);
	}

	@Transactional
	public Optional<MaritimeAiAssessmentResponse> retrieveScenario(AuthenticatedUser user, Long shipmentId,
			String assessmentId, MaritimeAiScenarioAssessmentContext expectedContext) {
		ShipmentViewAuthorization authorization = shipments.authorizeView(user, shipmentId);
		if (!Objects.equals(expectedContext.viewerCompanyId(), authorization.viewerCompanyId())
				|| !Objects.equals(expectedContext.managingCompanyId(), authorization.managingCompanyId())
				|| !Objects.equals(expectedContext.importerCompanyId(), authorization.importerCompanyId())) return Optional.empty();
		return assessments.findByAssessmentIdAndShipment_Id(assessmentId, shipmentId)
				.filter(value -> Objects.equals(value.getOwningCompanyId(), authorization.managingCompanyId()))
				.filter(value -> storedScenarioContext(value).filter(expectedContext::equals).isPresent())
				.filter(value -> historyAccess.canRead(value, authorization, historyScope(value.getOutputJson())))
				.map(this::response);
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
		return assessments.findByShipment_IdOrderByCreatedAtDesc(shipmentId).stream()
				.filter(this::isOrdinaryAssessment)
				.filter(value -> historyAccess.canRead(value, authorization, historyScope(value.getOutputJson())))
				.map(this::response).toList();
	}

	private MaritimeAiAssessmentResponse persist(ShipmentDetailResponse detail, MaritimeAiEvidencePackage input, Instant now,
			MaritimeAiClient.MaritimeAiClientResult result, MaritimeAiGenerationState state, String validation, MaritimeAiInterpretation output) {
		Shipment shipment = shipmentRepository.findById(detail.id()).orElseThrow();
		MaritimeAiDeterministicSummary summary = deterministicSummary(input);
		Long importerCompanyId = detail.importer() == null ? null : detail.importer().id();
		String outputJson = write(new MaritimeAiStoredAssessmentContent(output, summary,
				MaritimeAiAssessmentHistoryScope.captured(importerCompanyId), MaritimeAiStoredAssessmentContent.SHIPMENT_ASSESSMENT, null));
		String failure = output == null ? result.failure() : null;
		MaritimeAiAssessment saved = assessments.save(new MaritimeAiAssessment(shipment, UUID.randomUUID().toString(), input.bundleVersion(), hash(input),
				Instant.parse(input.assessmentCutoffUtc()), result.provider(), result.model(), result.promptVersion(), state, validation,
				MaritimeAiStoredAssessmentContent.SHIPMENT_ASSESSMENT, outputJson, failure, now));
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
	private Optional<MaritimeAiScenarioAssessmentContext> storedScenarioContext(MaritimeAiAssessment value) {
		try {
			if (!MaritimeAiStoredAssessmentContent.CONTROLLED_SCENARIO_ASSESSMENT.equals(value.getRecordType())) return Optional.empty();
			MaritimeAiStoredAssessmentContent content = readContent(value.getOutputJson());
			MaritimeAiScenarioAssessmentContext scenarioContext = content.scenarioContext();
			if (!MaritimeAiStoredAssessmentContent.CONTROLLED_SCENARIO_ASSESSMENT.equals(content.recordType())
					|| scenarioContext == null || !scenarioContext.hasValidShape()
					|| !Objects.equals(scenarioContext.contextHash(), hash(scenarioContext.hashMaterial()))
					|| !Objects.equals(scenarioContext.shipmentId(), value.getShipmentId())
					|| !Objects.equals(scenarioContext.managingCompanyId(), value.getOwningCompanyId())
					|| !Objects.equals(scenarioContext.bundleHash(), value.getBundleHash())
					|| !Objects.equals(scenarioContext.simulationCutoffUtc(), value.getAssessmentCutoffUtc().toString())) return Optional.empty();
			return Optional.of(scenarioContext);
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}
	private boolean isOrdinaryAssessment(MaritimeAiAssessment value) {
		if (!MaritimeAiStoredAssessmentContent.SHIPMENT_ASSESSMENT.equals(value.getRecordType())) return false;
		try {
			JsonNode root = json.readTree(value.getOutputJson());
			if (root == null || !root.isObject()) return false;
			JsonNode type = root.get("recordType");
			if (type != null && !type.isNull()
					&& (!type.isTextual() || !MaritimeAiStoredAssessmentContent.SHIPMENT_ASSESSMENT.equals(type.asText()))) return false;
			JsonNode scenarioContext = root.get("scenarioContext");
			return scenarioContext == null || scenarioContext.isNull();
		} catch (Exception exception) {
			// Unknown or malformed envelopes are hidden from ordinary history to fail closed.
			return false;
		}
	}
	private static boolean sameAuthorization(ShipmentViewAuthorization first, ShipmentViewAuthorization second) {
		return first != null && second != null
				&& Objects.equals(first.viewerCompanyId(), second.viewerCompanyId())
				&& Objects.equals(first.managingCompanyId(), second.managingCompanyId())
				&& Objects.equals(first.importerCompanyId(), second.importerCompanyId());
	}
	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) return first;
		return second == null || second.isBlank() ? null : second;
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
			if (tree.has("interpretation") || tree.has("deterministicSummary") || tree.has("recordType")
					|| tree.has("scenarioContext")) return json.readValue(value, MaritimeAiStoredAssessmentContent.class);
			return new MaritimeAiStoredAssessmentContent(json.readValue(value, MaritimeAiInterpretation.class), null, null);
		} catch (Exception ex) { throw new IllegalStateException("Stored AI output is invalid", ex); }
	}
	private String hash(Object value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)); return java.util.HexFormat.of().formatHex(digest); } catch (Exception ex) { throw new IllegalStateException(ex); } }
}
