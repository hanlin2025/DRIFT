package com.drift.backend.maritimeai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

@Service
public class MaritimeAiAssessmentService {
	private final ShipmentService shipments; private final ShipmentRepository shipmentRepository;
	private final MaritimeContextService context; private final MaritimeAiClient client; private final MaritimeAiAssessmentRepository assessments;
	private final ObjectMapper json; private final Clock clock; private final boolean enabled;

	public MaritimeAiAssessmentService(ShipmentService shipments, ShipmentRepository shipmentRepository, MaritimeContextService context,
			MaritimeAiClient client, MaritimeAiAssessmentRepository assessments, ObjectMapper json, ObjectProvider<Clock> clocks,
			@Value("${app.maritime-ai.enabled:false}") boolean enabled) {
		this.shipments = shipments; this.shipmentRepository = shipmentRepository; this.context = context; this.client = client;
		this.assessments = assessments; this.json = json; this.clock = clocks.getIfAvailable(Clock::systemUTC); this.enabled = enabled;
	}

	@Transactional
	public MaritimeAiAssessmentResponse generate(AuthenticatedUser user, Long shipmentId) {
		ShipmentDetailResponse detail = shipments.get(user, shipmentId); // authorization gate and existing 401/403/404 semantics
		Instant now = clock.instant();
		MaritimeContextResponse bundle = context.forShipment(detail);
		MaritimeAiShipmentContext shipmentContext = new MaritimeAiShipmentContext(detail.transshipmentPort(),
				new MaritimeAiShipmentContext.Vessel(detail.motherVessel(), detail.motherVesselImo(), detail.plannedMotherArrivalAt()),
				new MaritimeAiShipmentContext.Vessel(detail.feederVessel(), detail.feederVesselImo(), detail.plannedFeederDepartureAt()));
		MaritimeAiEvidencePackage fullInput = new MaritimeAiEvidencePackage(bundle.contractVersion(), bundle.assessmentCutoffUtc(),
				bundle.shipmentAssociationConfidence(), shipmentContext, bundle.evidence());
		if (!enabled) return transientResponse(fullInput, MaritimeAiGenerationState.ABSTAINED, "FEATURE_DISABLED", null, "AI generation is disabled for this environment.");
		// Until an approved live-source policy exists, never transmit recorded replay data.
		// Only sufficient, compatible synthetic demonstration evidence can reach a provider.
		List<MaritimeEvidence> eligible = eligibleForInference(bundle.evidence());
		MaritimeAiEvidencePackage input = new MaritimeAiEvidencePackage(bundle.contractVersion(), bundle.assessmentCutoffUtc(),
				bundle.shipmentAssociationConfidence(), shipmentContext, eligible);
		if (input.evidence().isEmpty()) {
			return persist(detail, input, now, MaritimeAiClient.MaritimeAiClientResult.unavailable("No sufficient approved synthetic evidence."), MaritimeAiGenerationState.ABSTAINED, "INSUFFICIENT_EVIDENCE", null);
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
		return evidence.stream()
				.filter(item -> "SYNTHETIC_DEMONSTRATION".equals(item.sourceMode()))
				.filter(item -> item.evidenceSufficient() && !"INCOMPATIBLE".equals(item.compatibilityState())
						&& !"STALE".equals(item.freshness()))
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
		String outputJson = output == null ? null : write(output);
		String failure = output == null ? result.failure() : null;
		MaritimeAiAssessment saved = assessments.save(new MaritimeAiAssessment(shipment, UUID.randomUUID().toString(), input.bundleVersion(), hash(input),
				Instant.parse(input.assessmentCutoffUtc()), result.provider(), result.model(), result.promptVersion(), state, validation, outputJson, failure, now));
		return response(saved);
	}

	private MaritimeAiAssessmentResponse transientResponse(MaritimeAiEvidencePackage input, MaritimeAiGenerationState state, String validation,
			MaritimeAiInterpretation output, String limitation) {
		return new MaritimeAiAssessmentResponse(null, state.name(), validation, input.bundleVersion(), hash(input), input.assessmentCutoffUtc(),
				"UNCONFIGURED", "", "cdg247.evidence.v1", null, output, limitation);
	}
	private MaritimeAiAssessmentResponse response(MaritimeAiAssessment value) {
		MaritimeAiInterpretation output = value.getOutputJson() == null ? null : read(value.getOutputJson());
		return new MaritimeAiAssessmentResponse(value.getAssessmentId(), value.getGenerationState().name(), value.getValidationStatus(),
				value.getBundleVersion(), value.getBundleHash(), value.getAssessmentCutoffUtc().toString(), value.getProvider(), value.getModel(), value.getPromptVersion(),
				value.getCreatedAt().toString(), output, value.getFailureMetadata());
	}
	private String write(Object value) { try { return json.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException("Could not serialize AI output", ex); } }
	private MaritimeAiInterpretation read(String value) { try { return json.readValue(value, MaritimeAiInterpretation.class); } catch (Exception ex) { throw new IllegalStateException("Stored AI output is invalid", ex); } }
	private String hash(MaritimeAiEvidencePackage value) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(write(value).getBytes(StandardCharsets.UTF_8)); return java.util.HexFormat.of().formatHex(digest); } catch (Exception ex) { throw new IllegalStateException(ex); } }
}
