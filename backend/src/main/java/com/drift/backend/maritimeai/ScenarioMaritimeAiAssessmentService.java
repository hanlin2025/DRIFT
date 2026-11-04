package com.drift.backend.maritimeai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.maritimecontext.MaritimeContextResponse;
import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.shipment.ControlledScenarioSessionResponse;
import com.drift.backend.shipment.ControlledScenarioSessionService;
import com.drift.backend.shipment.ControlledScenarioSnapshot;
import com.drift.backend.shipment.ShipmentViewAuthorization;
import com.drift.backend.shipment.exception.ControlledScenarioUnavailableException;
import com.drift.backend.shipment.exception.ScenarioTransitionConflictException;

/** Reauthorizes an exact scenario snapshot, then validates, persists, and retrieves explicit interpretations. */
@Service
public class ScenarioMaritimeAiAssessmentService {
	private static final int MAX_CACHE_ENTRIES = 128;
	private static final Duration CACHE_TTL = Duration.ofMinutes(10);

	private final ControlledScenarioSessionService sessions;
	private final MaritimeAiAssessmentService assessments;
	private final boolean enabled;
	private final Clock clock;
	private final Map<CacheKey, CacheEntry> cache = new ConcurrentHashMap<>();
	private final Object cacheLock = new Object();

	public ScenarioMaritimeAiAssessmentService(ControlledScenarioSessionService sessions,
			MaritimeAiAssessmentService assessments, ObjectProvider<Clock> clocks,
			@Value("${app.maritime-ai.scenario-enabled:false}") boolean enabled) {
		this.sessions = sessions;
		this.assessments = assessments;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
		this.enabled = enabled;
	}

	public ScenarioMaritimeAiResponse generate(AuthenticatedUser user, UUID sessionId, Long shipmentId,
			ScenarioMaritimeAiRequest request) {
		if (!enabled) throw unavailable("SCENARIO_AI_DISABLED", HttpStatus.NOT_FOUND,
				"Scenario AI interpretation is not available.");
		ControlledScenarioSessionResponse current = sessions.get(user, sessionId);
		SelectedSnapshot selected = select(current, shipmentId, request);
		MaritimeAiEvidencePackage input = input(selected);
		ShipmentViewAuthorization authorization = assessments.captureScenarioAuthorization(user, shipmentId);
		MaritimeAiScenarioAssessmentContext context = assessments.scenarioContext(user, current, selected.snapshot(),
				selected.row(), shipmentId, authorization, input);
		CacheKey key = new CacheKey(user.id(), context);
		CacheEntry entry = reserve(key);
		if (entry.owner()) {
			try {
				MaritimeAiScenarioGenerationResult generation = assessments.generateScenario(input, true);
				assertSameSnapshot(user, sessionId, key);
				MaritimeAiAssessmentResponse assessment = assessments.persistScenario(user, shipmentId, input, context,
						authorization, generation);
				entry.result().complete(response(context, assessment));
			} catch (RuntimeException failure) {
				cache.remove(key, entry);
				entry.result().completeExceptionally(failure);
			}
		}
		try {
			ScenarioMaritimeAiResponse response = entry.result().join();
			assertSameSnapshot(user, sessionId, key);
			return response;
		} catch (CompletionException failure) {
			Throwable cause = failure.getCause();
			if (cause instanceof RuntimeException runtime) throw runtime;
			throw failure;
		}
	}

	public ScenarioMaritimeAiResponse retrieve(AuthenticatedUser user, UUID sessionId, Long shipmentId, String assessmentId) {
		ControlledScenarioSessionResponse current = sessions.get(user, sessionId);
		MaritimeAiScenarioAssessmentContext stored = assessments.scenarioContext(assessmentId)
				.orElseThrow(this::assessmentNotFound);
		if (!sessionId.equals(stored.sessionId()) || !shipmentId.equals(stored.shipmentId())
				|| !user.id().equals(stored.userId()) || current.snapshot() == null) throw assessmentNotFound();
		ScenarioMaritimeAiRequest currentBinding = new ScenarioMaritimeAiRequest(stored.scenarioShipmentKey(),
				current.epochId(), current.revision(), current.snapshot().snapshotId(), current.snapshot().simulationCutoffUtc().toString());
		SelectedSnapshot selected;
		try { selected = select(current, shipmentId, currentBinding); }
		catch (ScenarioTransitionConflictException conflict) { throw assessmentNotFound(); }
		MaritimeAiEvidencePackage input = input(selected);
		ShipmentViewAuthorization authorization = assessments.captureScenarioAuthorization(user, shipmentId);
		MaritimeAiScenarioAssessmentContext expected = assessments.scenarioContext(user, current, selected.snapshot(),
				selected.row(), shipmentId, authorization, input);
		if (!stored.equals(expected)) throw assessmentNotFound();
		MaritimeAiAssessmentResponse assessment = assessments.retrieveScenario(user, shipmentId, assessmentId, expected)
				.orElseThrow(this::assessmentNotFound);
		CacheKey key = new CacheKey(user.id(), expected);
		assertSameSnapshot(user, sessionId, key);
		return response(expected, assessment);
	}

	private SelectedSnapshot select(ControlledScenarioSessionResponse session, Long shipmentId,
			ScenarioMaritimeAiRequest request) {
		if (request == null || shipmentId == null) throw unavailable("INVALID_REQUEST", HttpStatus.BAD_REQUEST,
				"A selected scenario shipment and snapshot binding are required.");
		if (!session.epochId().equals(request.expectedEpochId())) throw conflict("STALE_EPOCH",
				"The scenario session has been reset. Refresh before generating an interpretation.");
		ControlledScenarioSnapshot snapshot = session.snapshot();
		if (snapshot == null) throw conflict("SCENARIO_NOT_STARTED", "Start the scenario before requesting an interpretation.");
		if (session.revision() != request.expectedRevision()) throw conflict("STALE_REVISION",
				"The scenario changed. Refresh before generating an interpretation.");
		if (!snapshot.epochId().equals(session.epochId()) || snapshot.revision() != session.revision()) {
			throw conflict("STALE_SNAPSHOT", "The current session snapshot is internally inconsistent.");
		}
		if (!snapshot.snapshotId().equals(request.expectedSnapshotId())
				|| !snapshot.simulationCutoffUtc().toString().equals(request.expectedSimulationCutoffUtc())) {
			throw conflict("STALE_SNAPSHOT", "The scenario snapshot or simulation cutoff changed. Refresh before generating an interpretation.");
		}
		var binding = session.shipments().stream()
				.filter(item -> item.scenarioShipmentKey().equals(request.scenarioShipmentKey()))
				.findFirst().orElseThrow(() -> conflict("SCENARIO_SHIPMENT_MISMATCH",
						"The selected scenario shipment is not bound to this session."));
		if (!binding.shipmentId().equals(shipmentId)) throw conflict("SCENARIO_SHIPMENT_MISMATCH",
				"The selected shipment does not match the session binding.");
		ControlledScenarioSnapshot.ShipmentRead row = snapshot.shipments().stream()
				.filter(item -> item.scenarioShipmentKey().equals(request.scenarioShipmentKey()))
				.findFirst().orElseThrow(() -> conflict("SCENARIO_SHIPMENT_MISMATCH",
						"The selected scenario shipment has no snapshot facts."));
		if (!shipmentId.equals(row.sourceShipmentId())
				|| !binding.scenarioShipmentReference().equals(row.scenarioShipmentReference())) {
			throw conflict("SCENARIO_SHIPMENT_MISMATCH", "The scenario snapshot does not match the authorized shipment binding.");
		}
		return new SelectedSnapshot(session, snapshot, row);
	}

	private MaritimeAiEvidencePackage input(SelectedSnapshot selected) {
		ControlledScenarioSnapshot snapshot = selected.snapshot();
		ControlledScenarioSnapshot.ShipmentRead row = selected.row();
		MaritimeContextResponse bundle = snapshot.maritimeContext();
		if (bundle == null || !bundle.enabled() || !"SYNTHETIC_DEMONSTRATION".equals(bundle.sourceMode())
				|| !snapshot.simulationCutoffUtc().toString().equals(bundle.assessmentCutoffUtc())
				|| bundle.evidence() == null || bundle.evidence().stream()
					.anyMatch(item -> !"SYNTHETIC_DEMONSTRATION".equals(item.sourceMode()))) {
			throw unavailable("SCENARIO_AI_SYNTHETIC_EVIDENCE_REQUIRED", HttpStatus.SERVICE_UNAVAILABLE,
				"The selected scenario does not have a consistent synthetic evidence bundle at its cutoff.");
		}
		AisPosition mother = observation(snapshot, "MOTHER");
		AisPosition feeder = observation(snapshot, "FEEDER");
		var shipmentContext = new MaritimeAiShipmentContext(row.transshipmentPort(),
				new MaritimeAiShipmentContext.Vessel("MOTHER", mother == null ? null : mother.vesselName(), null,
						"PLANNED_ARRIVAL", row.plannedMotherArrivalAt()),
				new MaritimeAiShipmentContext.Vessel("FEEDER", feeder == null ? null : feeder.vesselName(), null,
						"PLANNED_DEPARTURE", row.plannedFeederDepartureAt()),
				MaritimeAiConnectionRiskContext.fromScenario(row),
				MaritimeAiBusinessContext.fromScenario(row.businessImpact(), row.transshipmentPort()),
				new MaritimeAiShipmentContext.Scenario(row.scenarioShipmentKey(), row.scenarioShipmentReference(),
					snapshot.stage().name(), snapshot.simulationCutoffUtc().toString()));
		List<MaritimeEvidence> evidence = List.copyOf(bundle.evidence());
		return new MaritimeAiEvidencePackage(bundle.contractVersion(), bundle.assessmentCutoffUtc(),
				bundle.shipmentAssociationConfidence(), shipmentContext, evidence);
	}

	private static AisPosition observation(ControlledScenarioSnapshot snapshot, String role) {
		return snapshot.aisObservations().stream().filter(item -> role.equals(item.vesselRole()))
				.map(ControlledScenarioSnapshot.AisObservationRead::position).findFirst().orElse(null);
	}

	private void assertSameSnapshot(AuthenticatedUser user, UUID sessionId, CacheKey key) {
		ControlledScenarioSessionResponse current = sessions.get(user, sessionId);
		MaritimeAiScenarioAssessmentContext expected = key.context();
		if (!current.epochId().equals(expected.epochId())) throw conflict("STALE_EPOCH",
				"The scenario session was reset while the interpretation was being generated.");
		if (current.revision() != expected.revision()) throw conflict("STALE_REVISION",
				"The scenario advanced while the interpretation was being generated.");
		ControlledScenarioSnapshot snapshot = current.snapshot();
		if (snapshot == null || !snapshot.snapshotId().equals(expected.snapshotId())
				|| !snapshot.simulationCutoffUtc().toString().equals(expected.simulationCutoffUtc())) {
			throw conflict("STALE_SNAPSHOT", "The scenario snapshot changed while the interpretation was being generated.");
		}
		if (current.shipments().stream().noneMatch(binding -> binding.scenarioShipmentKey().equals(expected.scenarioShipmentKey())
				&& binding.shipmentId().equals(expected.shipmentId()))) {
			throw conflict("SCENARIO_SHIPMENT_MISMATCH", "The authorized scenario shipment binding changed.");
		}
		SelectedSnapshot selected = select(current, expected.shipmentId(), new ScenarioMaritimeAiRequest(
				expected.scenarioShipmentKey(), expected.epochId(), expected.revision(), expected.snapshotId(), expected.simulationCutoffUtc()));
		MaritimeAiEvidencePackage currentInput = input(selected);
		ShipmentViewAuthorization authorization = assessments.captureScenarioAuthorization(user, expected.shipmentId());
		MaritimeAiScenarioAssessmentContext actual = assessments.scenarioContext(user, current, selected.snapshot(), selected.row(),
				expected.shipmentId(), authorization, currentInput);
		if (!expected.equals(actual)) throw conflict("STALE_SNAPSHOT", "The scenario context changed while the interpretation was generated.");
	}

	private ScenarioMaritimeAiResponse response(MaritimeAiScenarioAssessmentContext context, MaritimeAiAssessmentResponse assessment) {
		return new ScenarioMaritimeAiResponse(context.sessionId(), context.epochId(), context.snapshotId(), context.revision(),
				context.simulationCutoffUtc(), context.scenarioShipmentKey(), context.shipmentId(), assessment,
				context.scenarioId(), context.scenarioVersion(), context.scenarioShipmentReference(), context.stage(),
				context.evidenceHash(), context.contextHash());
	}

	private CacheEntry reserve(CacheKey key) {
		synchronized (cacheLock) {
			Instant now = clock.instant();
			cache.entrySet().removeIf(item -> !item.getValue().result().isDone()
					? false : item.getValue().createdAt().plus(CACHE_TTL).isBefore(now));
			CacheEntry existing = cache.get(key);
			if (existing != null) return existing.asFollower();
			if (cache.size() >= MAX_CACHE_ENTRIES) throw unavailable("SCENARIO_AI_CAPACITY", HttpStatus.TOO_MANY_REQUESTS,
					"Scenario interpretation capacity is temporarily full. Try again later.");
			CacheEntry created = CacheEntry.owner(now);
			cache.put(key, created);
			return created;
		}
	}

	private static ScenarioTransitionConflictException conflict(String code, String message) {
		return new ScenarioTransitionConflictException(code, message);
	}
	private static ControlledScenarioUnavailableException unavailable(String code, HttpStatus status, String message) {
		return new ControlledScenarioUnavailableException(code, status.value(), message);
	}
	private ControlledScenarioUnavailableException assessmentNotFound() {
		return unavailable("SCENARIO_ASSESSMENT_NOT_FOUND", HttpStatus.NOT_FOUND,
				"The scenario assessment is not available in this session and snapshot.");
	}

	private record SelectedSnapshot(ControlledScenarioSessionResponse session,
			ControlledScenarioSnapshot snapshot, ControlledScenarioSnapshot.ShipmentRead row) { }
	private record CacheKey(Long userId, MaritimeAiScenarioAssessmentContext context) { }
	private record CacheEntry(Instant createdAt, CompletableFuture<ScenarioMaritimeAiResponse> result, boolean owner) {
		private static CacheEntry owner(Instant now) { return new CacheEntry(now, new CompletableFuture<>(), true); }
		private CacheEntry asFollower() { return owner ? new CacheEntry(createdAt, result, false) : this; }
	}
}
