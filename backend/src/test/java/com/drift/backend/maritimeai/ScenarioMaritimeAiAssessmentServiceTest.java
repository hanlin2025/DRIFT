package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.anyBoolean;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpStatus;

import com.drift.backend.account.Role;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.maritimecontext.MaritimeContextResponse;
import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.shipment.ArrivalEstimate;
import com.drift.backend.shipment.ConnectionWindow;
import com.drift.backend.shipment.ConnectionWindowCalculation;
import com.drift.backend.shipment.ControlledScenarioCapabilities;
import com.drift.backend.shipment.ControlledScenarioSessionResponse;
import com.drift.backend.shipment.ControlledScenarioSessionService;
import com.drift.backend.shipment.ControlledScenarioSessionState;
import com.drift.backend.shipment.ControlledScenarioSnapshot;
import com.drift.backend.shipment.ControlledScenarioStage;
import com.drift.backend.shipment.DelayComparison;
import com.drift.backend.shipment.RemainingConnectionWindow;
import com.drift.backend.shipment.ShipmentRisk;
import com.drift.backend.shipment.ShipmentViewAuthorization;
import com.drift.backend.shipment.exception.ControlledScenarioUnavailableException;
import com.drift.backend.shipment.exception.ScenarioTransitionConflictException;

class ScenarioMaritimeAiAssessmentServiceTest {
	private static final UUID SESSION_ID = UUID.fromString("d5f2390b-d610-4c5d-9b6c-7153f8a89fbf");
	private static final UUID EPOCH_ID = UUID.fromString("74dab1c6-1d4b-4889-8dde-0b31986fed28");
	private static final UUID SNAPSHOT_ID = UUID.fromString("55bb0888-2906-4ba0-946f-b054b0ef1272");
	private static final Instant CUTOFF = Instant.parse("2026-10-01T04:00:00Z");
	private static final AuthenticatedUser OWNER = new AuthenticatedUser(8L, "owner@example.invalid", Role.IMPORTER, Instant.MAX);
	private static final ShipmentViewAuthorization AUTHORIZATION = new ShipmentViewAuthorization(1L, 1L, null);
	private static final MaritimeAiScenarioAssessmentContext CONTEXT = context();
	private static final MaritimeAiScenarioGenerationResult GENERATION = new MaritimeAiScenarioGenerationResult(
			new MaritimeAiClient.MaritimeAiClientResult("openai-responses", "test-model", "scenario-prompt", null, "unavailable"),
			MaritimeAiGenerationState.ABSTAINED, "MODEL_UNAVAILABLE", null, "Provider unavailable.");

	@Test
	void bindsBInterpretationToExactSnapshotAndBackendBusinessFacts() {
		var sessions = mock(ControlledScenarioSessionService.class);
		var ai = mock(MaritimeAiAssessmentService.class);
		var session = session(snapshot(2, "2026-10-02T10:00:00Z", DelayComparison.Outcome.NO_PREDICTED_IMPACT));
		when(sessions.get(OWNER, SESSION_ID)).thenReturn(session, session, session);
		MaritimeAiAssessmentResponse generated = response("GENERATED", "VALID", "openai-responses");
		stubPersistence(ai, generated);
		var service = service(sessions, ai, true);

		ScenarioMaritimeAiResponse response = service.generate(OWNER, SESSION_ID, 200L,
				request("B", 2, SNAPSHOT_ID, CUTOFF.toString()));

		assertThat(response.shipmentId()).isEqualTo(200L);
		assertThat(response.scenarioShipmentKey()).isEqualTo("B");
		assertThat(response.assessment().assessmentId()).isNotNull();
		assertThat(response.assessment().state()).isEqualTo("GENERATED");
		var captor = org.mockito.ArgumentCaptor.forClass(MaritimeAiEvidencePackage.class);
		verify(ai).generateScenario(captor.capture(), eq(true));
		MaritimeAiEvidencePackage input = captor.getValue();
		assertThat(input.assessmentCutoffUtc()).isEqualTo(CUTOFF.toString());
		assertThat(input.evidence()).extracting(MaritimeEvidence::sourceMode).containsOnly("SYNTHETIC_DEMONSTRATION");
		assertThat(input.shipmentContext().scenario()).isEqualTo(new MaritimeAiShipmentContext.Scenario(
				"B", "SYNTH-B", "CHANGE", CUTOFF.toString()));
		assertThat(input.shipmentContext().connectionRisk().riskLevel()).isEqualTo(ShipmentRisk.Level.MEDIUM);
		assertThat(input.shipmentContext().connectionRisk().remainingWindow().remainingSeconds()).isEqualTo(72_000L);
		assertThat(input.shipmentContext().businessContext().deadline().instant()).isEqualTo(OffsetDateTime.parse("2026-10-02T10:00:00Z"));
		assertThat(input.shipmentContext().businessContext().comparison().outcome()).isEqualTo(DelayComparison.Outcome.NO_PREDICTED_IMPACT);
		verify(sessions, times(3)).get(OWNER, SESSION_ID);
	}

	@Test
	void emptySyntheticBaselineBundleReachesTheInsufficientEvidencePath() {
		var sessions = mock(ControlledScenarioSessionService.class);
		var ai = mock(MaritimeAiAssessmentService.class);
		ControlledScenarioSessionResponse baseline = session(baselineSnapshot());
		when(sessions.get(OWNER, SESSION_ID)).thenReturn(baseline, baseline, baseline);
		when(ai.generateScenarioTransient(any(), eq(true)))
				.thenReturn(response("ABSTAINED", "INSUFFICIENT_EVIDENCE", "controlled-replay-stub"));
		var service = service(sessions, ai, true);

		ScenarioMaritimeAiResponse response = service.generate(OWNER, SESSION_ID, 200L,
				request("B", 2, SNAPSHOT_ID, CUTOFF.toString()));

		assertThat(response.assessment().state()).isEqualTo("ABSTAINED");
		assertThat(response.assessment().validationStatus()).isEqualTo("INSUFFICIENT_EVIDENCE");
		var captor = org.mockito.ArgumentCaptor.forClass(MaritimeAiEvidencePackage.class);
		verify(ai).generateScenarioTransient(captor.capture(), eq(true));
		assertThat(captor.getValue().evidence()).isEmpty();
		assertThat(captor.getValue().shipmentContext().scenario().stage()).isEqualTo("BASELINE");
	}

	@Test
	void rejectsNonEmptySyntheticEvidenceAtBaseline() {
		var sessions = mock(ControlledScenarioSessionService.class);
		var ai = mock(MaritimeAiAssessmentService.class);
		ControlledScenarioSnapshot baseline = baselineSnapshot();
		MaritimeContextResponse evidenceBundle = new MaritimeContextResponse("cdg246.maritime-evidence-bundle.v1", true,
				"SYNTHETIC_DEMONSTRATION", CUTOFF.toString(), "VERIFIED", "Baseline must not carry evidence.",
				List.of(syntheticEvidence()), List.of(), null, null);
		when(sessions.get(OWNER, SESSION_ID)).thenReturn(session(withBundle(baseline, evidenceBundle)));

		assertThatThrownBy(() -> service(sessions, ai, true).generate(OWNER, SESSION_ID, 200L,
				request("B", 2, SNAPSHOT_ID, CUTOFF.toString())))
				.isInstanceOf(ControlledScenarioUnavailableException.class).extracting("code")
				.isEqualTo("SCENARIO_AI_SYNTHETIC_EVIDENCE_REQUIRED");
		verify(ai, never()).generateScenarioTransient(any(), anyBoolean());
	}

	@Test
	void rejectsDisabledUnavailableBundleAtBaseline() {
		var sessions = mock(ControlledScenarioSessionService.class);
		var ai = mock(MaritimeAiAssessmentService.class);
		ControlledScenarioSnapshot baseline = baselineSnapshot();
		MaritimeContextResponse disabledBundle = new MaritimeContextResponse("cdg246.maritime-evidence-bundle.v1", false,
				"UNAVAILABLE", CUTOFF.toString(), "UNAVAILABLE", "Maritime context feature is disabled.",
				List.of(), List.of(), null, null);
		when(sessions.get(OWNER, SESSION_ID)).thenReturn(session(withBundle(baseline, disabledBundle)));

		assertThatThrownBy(() -> service(sessions, ai, true).generate(OWNER, SESSION_ID, 200L,
				request("B", 2, SNAPSHOT_ID, CUTOFF.toString())))
				.isInstanceOf(ControlledScenarioUnavailableException.class).extracting("code")
				.isEqualTo("SCENARIO_AI_SYNTHETIC_EVIDENCE_REQUIRED");
		verify(ai, never()).generateScenarioTransient(any(), anyBoolean());
	}

	@Test
	void rejectsBaselineBundleWithMismatchedCutoff() {
		var sessions = mock(ControlledScenarioSessionService.class);
		var ai = mock(MaritimeAiAssessmentService.class);
		ControlledScenarioSnapshot baseline = baselineSnapshot();
		MaritimeContextResponse wrongCutoff = new MaritimeContextResponse("cdg246.maritime-evidence-bundle.v1", true,
				"UNAVAILABLE", "2026-10-01T04:01:00Z", "UNAVAILABLE", "Cutoff differs from the scenario.",
				List.of(), List.of(), null, null);
		when(sessions.get(OWNER, SESSION_ID)).thenReturn(session(withBundle(baseline, wrongCutoff)));

		assertThatThrownBy(() -> service(sessions, ai, true).generate(OWNER, SESSION_ID, 200L,
				request("B", 2, SNAPSHOT_ID, CUTOFF.toString())))
				.isInstanceOf(ControlledScenarioUnavailableException.class).extracting("code")
				.isEqualTo("SCENARIO_AI_SYNTHETIC_EVIDENCE_REQUIRED");
		verify(ai, never()).generateScenarioTransient(any(), anyBoolean());
	}

	private static ControlledScenarioSnapshot withBundle(ControlledScenarioSnapshot snapshot, MaritimeContextResponse bundle) {
		return new ControlledScenarioSnapshot(snapshot.snapshotId(), snapshot.epochId(), snapshot.revision(), snapshot.stage(),
				snapshot.stageOrder(), snapshot.stageCount(), snapshot.fixtureStageId(), snapshot.simulationCutoffUtc(),
				snapshot.mapPlaybackCutoffUtc(), snapshot.shipments(), snapshot.aisObservations(), bundle);
	}

	@Test
	void rejectsAStaleStageDuringInferenceBeforeReturningTheResult() {
		var sessions = mock(ControlledScenarioSessionService.class);
		var ai = mock(MaritimeAiAssessmentService.class);
		ControlledScenarioSessionResponse before = session(snapshot(2, "2026-10-02T10:00:00Z", DelayComparison.Outcome.NO_PREDICTED_IMPACT));
		ControlledScenarioSessionResponse after = session(snapshot(3, "2026-10-02T10:00:00Z", DelayComparison.Outcome.NO_PREDICTED_IMPACT));
		when(sessions.get(OWNER, SESSION_ID)).thenReturn(before, after);
		stubPersistence(ai, response("GENERATED", "VALID", "openai-responses"));
		var service = service(sessions, ai, true);

		assertThatThrownBy(() -> service.generate(OWNER, SESSION_ID, 200L,
				request("B", 2, SNAPSHOT_ID, CUTOFF.toString())))
				.isInstanceOf(ScenarioTransitionConflictException.class).extracting("code").isEqualTo("STALE_REVISION");
		verify(ai).generateScenario(any(), eq(true));
	}

	@Test
	void cachesOneSnapshotResultSoAnExplicitRetryDoesNotRepeatInference() {
		var sessions = mock(ControlledScenarioSessionService.class);
		var ai = mock(MaritimeAiAssessmentService.class);
		var current = session(snapshot(2, "2026-10-02T10:00:00Z", DelayComparison.Outcome.NO_PREDICTED_IMPACT));
		when(sessions.get(OWNER, SESSION_ID)).thenReturn(current, current, current, current, current);
		MaritimeAiAssessmentResponse generated = response("GENERATED", "VALID", "openai-responses");
		stubPersistence(ai, generated);
		var service = service(sessions, ai, true);
		var request = request("B", 2, SNAPSHOT_ID, CUTOFF.toString());

		ScenarioMaritimeAiResponse first = service.generate(OWNER, SESSION_ID, 200L, request);
		ScenarioMaritimeAiResponse retry = service.generate(OWNER, SESSION_ID, 200L, request);

		assertThat(retry).isEqualTo(first);
		verify(ai, times(1)).generateScenario(any(), eq(true));
	}

	@Test
	void disabledScenarioAiAndStaleRequestNeverInvokeTheAiService() {
		var sessions = mock(ControlledScenarioSessionService.class);
		var ai = mock(MaritimeAiAssessmentService.class);
		assertThatThrownBy(() -> service(sessions, ai, false).generate(OWNER, SESSION_ID, 200L,
				request("B", 2, SNAPSHOT_ID, CUTOFF.toString())))
				.isInstanceOf(ControlledScenarioUnavailableException.class).extracting("status").isEqualTo(HttpStatus.NOT_FOUND.value());
		verify(sessions, never()).get(any(), any());
		verify(ai, never()).generateScenario(any(), anyBoolean());

		var current = session(snapshot(2, "2026-10-02T10:00:00Z", DelayComparison.Outcome.NO_PREDICTED_IMPACT));
		when(sessions.get(OWNER, SESSION_ID)).thenReturn(current);
		assertThatThrownBy(() -> service(sessions, ai, true).generate(OWNER, SESSION_ID, 200L,
				request("B", 1, SNAPSHOT_ID, CUTOFF.toString())))
				.isInstanceOf(ScenarioTransitionConflictException.class).extracting("code").isEqualTo("STALE_REVISION");
		verify(ai, never()).generateScenario(any(), anyBoolean());
	}

	@Test
	void refusesMixedOrNonSyntheticEvidenceAndRejectsAPathBindingMismatch() {
		var sessions = mock(ControlledScenarioSessionService.class);
		var ai = mock(MaritimeAiAssessmentService.class);
		var mixed = session(snapshot(2, "2026-10-02T10:00:00Z", DelayComparison.Outcome.NO_PREDICTED_IMPACT,
				new MaritimeEvidence("MPA_ETA_REVISION", "LIVE", "live-1", "live", "source", "", "citation", null, null, null,
					"FRESH", null, null, null, "UNKNOWN", "UNKNOWN", "UNKNOWN", "UNKNOWN", true, true, "VERIFIED", "Live data.")));
		when(sessions.get(OWNER, SESSION_ID)).thenReturn(mixed);
		assertThatThrownBy(() -> service(sessions, ai, true).generate(OWNER, SESSION_ID, 200L,
				request("B", 2, SNAPSHOT_ID, CUTOFF.toString())))
				.isInstanceOf(ControlledScenarioUnavailableException.class).extracting("code")
				.isEqualTo("SCENARIO_AI_SYNTHETIC_EVIDENCE_REQUIRED");
verify(ai, never()).generateScenarioTransient(any(), anyBoolean());

		when(sessions.get(OWNER, SESSION_ID)).thenReturn(session(snapshot(2, "2026-10-02T10:00:00Z", DelayComparison.Outcome.NO_PREDICTED_IMPACT)));
		assertThatThrownBy(() -> service(sessions, ai, true).generate(OWNER, SESSION_ID, 100L,
				request("B", 2, SNAPSHOT_ID, CUTOFF.toString())))
				.isInstanceOf(ScenarioTransitionConflictException.class).extracting("code").isEqualTo("SCENARIO_SHIPMENT_MISMATCH");
		verify(ai, never()).generateScenario(any(), anyBoolean());
	}

	private static void stubPersistence(MaritimeAiAssessmentService ai, MaritimeAiAssessmentResponse response) {
		when(ai.captureScenarioAuthorization(eq(OWNER), eq(200L))).thenReturn(AUTHORIZATION);
		when(ai.scenarioContext(eq(OWNER), any(), any(), any(), eq(200L), eq(AUTHORIZATION), any())).thenReturn(CONTEXT);
		when(ai.generateScenario(any(), eq(true))).thenReturn(GENERATION);
		when(ai.persistScenario(eq(OWNER), eq(200L), any(), eq(CONTEXT), eq(AUTHORIZATION), eq(GENERATION)))
				.thenReturn(response);
	}

	private static ScenarioMaritimeAiAssessmentService service(ControlledScenarioSessionService sessions,
			MaritimeAiAssessmentService ai, boolean enabled) {
		var beans = new DefaultListableBeanFactory();
		return new ScenarioMaritimeAiAssessmentService(sessions, ai, beans.getBeanProvider(Clock.class), enabled);
	}

	private static ScenarioMaritimeAiRequest request(String key, long revision, UUID snapshotId, String cutoff) {
		return new ScenarioMaritimeAiRequest(key, EPOCH_ID, revision, snapshotId, cutoff);
	}

	private static ControlledScenarioSessionResponse session(ControlledScenarioSnapshot snapshot) {
		return new ControlledScenarioSessionResponse("1", SESSION_ID, "CDG279-Api", "cdg279.synthetic-disruption.v1",
				ControlledScenarioSessionState.ACTIVE, EPOCH_ID, snapshot.revision(),
				new ControlledScenarioCapabilities(true, false, true, true, false), List.of(
					new ControlledScenarioSessionResponse.ShipmentBinding("A", 100L, "ANCHOR-A", "SYNTH-A"),
					new ControlledScenarioSessionResponse.ShipmentBinding("B", 200L, "ANCHOR-B", "SYNTH-B")), snapshot);
	}

	private static ControlledScenarioSnapshot snapshot(long revision, String deadline, DelayComparison.Outcome outcome,
			MaritimeEvidence... overrideEvidence) {
		var a = row("A", 100L, "SYNTH-A", "2026-10-02T08:00:00Z", DelayComparison.Outcome.EXCEEDS_DEADLINE);
		var b = row("B", 200L, "SYNTH-B", deadline, outcome);
		List<MaritimeEvidence> evidence = overrideEvidence.length == 0 ? List.of(syntheticEvidence()) : List.of(overrideEvidence);
		var bundle = new MaritimeContextResponse("cdg246.maritime-evidence-bundle.v1", true, "SYNTHETIC_DEMONSTRATION",
				CUTOFF.toString(), "VERIFIED", "Fictional scenario context.", evidence, List.of(), null, null);
		AisPosition mother = new AisPosition("990279001", "Synthetic mother", BigDecimal.ONE, BigDecimal.ONE,
				BigDecimal.valueOf(3), BigDecimal.ZERO, 0, 0, true, 0, CUTOFF, AisPositionSource.SEEDED);
		AisPosition feeder = new AisPosition("990279002", "Synthetic feeder", BigDecimal.ONE, BigDecimal.ONE,
				BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, true, 0, CUTOFF, AisPositionSource.SEEDED);
		return new ControlledScenarioSnapshot(SNAPSHOT_ID, EPOCH_ID, revision, ControlledScenarioStage.CHANGE, 1, 3,
				"change", CUTOFF, CUTOFF, List.of(a, b), List.of(
					new ControlledScenarioSnapshot.AisObservationRead("MOTHER", CUTOFF, CUTOFF, "SYNTHETIC_DEMONSTRATION", mother),
					new ControlledScenarioSnapshot.AisObservationRead("FEEDER", CUTOFF, CUTOFF, "SYNTHETIC_DEMONSTRATION", feeder)), bundle);
	}

	private static ControlledScenarioSnapshot baselineSnapshot() {
		ControlledScenarioSnapshot changed = snapshot(2, "2026-10-02T10:00:00Z", DelayComparison.Outcome.NO_PREDICTED_IMPACT);
		MaritimeContextResponse bundle = new MaritimeContextResponse("cdg246.maritime-evidence-bundle.v1", true,
				"UNAVAILABLE", CUTOFF.toString(), "UNAVAILABLE", "No evidence is supplied at baseline.", List.of(),
				List.of(), null, null);
		return new ControlledScenarioSnapshot(SNAPSHOT_ID, EPOCH_ID, 2, ControlledScenarioStage.BASELINE, 0, 3,
				"baseline", CUTOFF, CUTOFF, changed.shipments(), changed.aisObservations(), bundle);
	}

	private static ControlledScenarioSnapshot.ShipmentRead row(String key, Long id, String reference, String deadline,
			DelayComparison.Outcome outcome) {
		var planned = new ConnectionWindowCalculation(ConnectionWindowCalculation.Status.AVAILABLE,
				new ConnectionWindow("30 hours", 108_000L));
		var remaining = new RemainingConnectionWindow(RemainingConnectionWindow.Status.AVAILABLE,
				"20 hours", 72_000L, null, null);
		var estimate = new ArrivalEstimate(OffsetDateTime.parse("2026-10-01T16:00:00Z"), ArrivalEstimate.LIMITATION,
				BigDecimal.valueOf(3), ArrivalEstimate.Classification.BEHIND_PLAN, "Behind plan", 36_000L, 0, CUTOFF, false, null);
		var impact = new DelayComparison(outcome, null, "Scenario comparison", estimate.estimatedArrivalAt(),
				OffsetDateTime.parse(deadline), "Synthetic scenario port");
		return new ControlledScenarioSnapshot.ShipmentRead(key, id, reference, "Synthetic scenario port",
				OffsetDateTime.parse("2026-10-01T06:00:00Z"), OffsetDateTime.parse("2026-10-02T12:00:00Z"),
				planned, remaining, estimate, new ShipmentRisk(ShipmentRisk.Level.MEDIUM, "Backend-calculated scenario risk."), impact);
	}

	private static MaritimeEvidence syntheticEvidence() {
		return new MaritimeEvidence("MPA_ETA_REVISION", "SYNTHETIC_DEMONSTRATION", "synthetic-1", "Fictional revision", "fixture", "", "Fixture citation",
				"2026-10-01T06:00:00Z", "2026-10-01T06:00:00Z", "2026-10-01T04:00:00Z", "FRESH", "old", "new", "change",
				"COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true, "VERIFIED", "Fictional evidence only.",
				"Synthetic mother", "MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION");
	}

	private static MaritimeAiAssessmentResponse response(String state, String validation, String provider) {
		return new MaritimeAiAssessmentResponse(UUID.randomUUID().toString(), state, validation, "bundle", "hash", CUTOFF.toString(), provider,
				"model", "prompt", Instant.now().toString(), null, null, null);
	}

	private static MaritimeAiScenarioAssessmentContext context() {
		String hash = "a".repeat(64);
		return new MaritimeAiScenarioAssessmentContext(1, "CDG279-Api", "cdg279.synthetic-disruption.v1", SESSION_ID, OWNER.id(),
				EPOCH_ID, 2, SNAPSHOT_ID, CUTOFF.toString(), "B", "SYNTH-B", "CHANGE", 200L,
				1L, 1L, null, hash, hash, hash);
	}
}
