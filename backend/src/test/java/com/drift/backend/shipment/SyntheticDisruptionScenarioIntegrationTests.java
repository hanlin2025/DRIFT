package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.maritimeai.MaritimeAiAssessmentResponse;
import com.drift.backend.maritimeai.MaritimeAiBusinessContext;
import com.drift.backend.maritimeai.MaritimeAiConnectionRiskContext;
import com.drift.backend.maritimeai.MaritimeAiEvidencePackage;
import com.drift.backend.maritimeai.ScenarioQualityStubClient;
import com.drift.backend.maritimeai.SyntheticDisruptionAiClient;
import com.drift.backend.maritimecontext.MaritimeContextResponse;
import com.drift.backend.maritimecontext.MaritimeEvidence;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.Scenario;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.Stage;
import com.drift.backend.shipment.exception.ShipmentConstraintEditForbiddenException;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Authenticated replay through real shipment, AIS persistence, risk, context, validation, and history paths. */
@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG279_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG279_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG279_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG279_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG279_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG279_DB_PASSWORD}",
		"spring.flyway.out-of-order=false",
		"app.ais.enabled=false",
		"app.risk-alerts.activation.command=false",
		"app.risk-alerts.evaluation.enabled=false",
		"app.risk-alerts.mail.enabled=false",
		"app.risk-alerts.mail.dispatch-enabled=false",
		"app.maritime-context.demo-enabled=false",
		"app.controlled-scenario.enabled=false",
		"app.maritime-ai.enabled=true",
		"app.maritime-ai.scenario-enabled=true",
		"app.maritime-ai.controlled-replay-stub-enabled=false",
		"app.maritime-ai.external-requests-approved=false",
		"app.maritime-ai.provider=",
		"app.maritime-ai.model=",
		"app.maritime-ai.endpoint=",
		"app.maritime-ai.api-key=",
		"app.maritime-ai.authentic-retrospective-inference-approved=false",
		"app.maritime-ai.authentic-retrospective-source-types-approved=",
		"app.maritime-ai.authentic-retrospective-evidence-ids-approved=",
		"app.jwt.expiration-ms=604800000",
		"drift.startup-guard.expected-url=${DRIFT_CDG279_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG279_OWNERSHIP_TOKEN}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = SyntheticDisruptionDatabaseGuard.class)
@Import({ SyntheticDisruptionScenarioIntegrationTests.ScenarioConfiguration.class, SyntheticPortCatalogTestConfiguration.class })
@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SyntheticDisruptionScenarioIntegrationTests {
	private static final Scenario SCENARIO = SyntheticDisruptionScenarioFixture.create("Integration1");
	private static final MutableScenarioClock TEST_CLOCK = new MutableScenarioClock(Instant.parse("2026-10-01T00:00:00Z"));
	private static final SyntheticDisruptionAiClient OFFLINE_CLIENT = new SyntheticDisruptionAiClient();
	private static final ScenarioQualityStubClient SCENARIO_AI_CLIENT = new ScenarioQualityStubClient(OFFLINE_CLIENT);
	private static final String PORT = SyntheticDisruptionScenarioFixture.PORT;
	private static final String PASSWORD = "Example123";
	private static final List<ExpectedStage> EXPECTED = List.of(
			new ExpectedStage("2026-10-01T06:00:00Z", 108_000, "LOW", "NO_PREDICTED_IMPACT", "NO_PREDICTED_IMPACT"),
			new ExpectedStage("2026-10-01T16:00:00Z", 72_000, "MEDIUM", "EXCEEDS_DEADLINE", "NO_PREDICTED_IMPACT"),
			new ExpectedStage("2026-10-02T08:00:00Z", 14_400, "HIGH", "EXCEEDS_DEADLINE", "NO_PREDICTED_IMPACT"));

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired ObjectMapper json;
	@Autowired LatestAisPositions positions;
	@Autowired ControlledScenarioProperties controlledScenarioProperties;
	@Autowired ControlledScenarioEmailProperties controlledScenarioEmailProperties;
	@Autowired JavaMailSender controlledScenarioMailSender;
	@Autowired PlatformTransactionManager transactionManager;

	@AfterEach
	void disableControlledScenarioAfterTest() {
		controlledScenarioEmailProperties.setEnabled(false);
		controlledScenarioEmailProperties.setRecipients("");
		reset(controlledScenarioMailSender);
		controlledScenarioProperties.setEnabled(false);
		controlledScenarioProperties.setDemoCompanyId(null);
		controlledScenarioProperties.setAllowedUserIds(Set.of());
		controlledScenarioProperties.setShipmentAId(null);
		controlledScenarioProperties.setShipmentBId(null);
	}

	@Test
	@Order(100)
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void sendsOneAllowlistedSyntheticEmailOnlyOnCommittedWorsenedTransition() throws Exception {
		reset(controlledScenarioMailSender);
		OFFLINE_CLIENT.clear();
		SCENARIO_AI_CLIENT.clearScenarioInputs();
		Identity owner = createIdentity();
		Identity otherTenant = createIdentity();
		List<Long> shipmentIds = createAndAuthorizeScenarioShipments(owner);
		configureControlledScenario(owner, shipmentIds);
		controlledScenarioEmailProperties.setEnabled(true);
		controlledScenarioEmailProperties.setRecipients(" demo@example.test,DEMO@example.test,invalid-address ");
		UUID sessionId = UUID.randomUUID();
		JsonNode created = scenarioCreate(sessionId, owner.managerToken(), shipmentIds.get(0));
		JsonNode baseline = scenarioCommand(sessionId, owner.managerToken(), "start", created, "mail-start", 200);
		assertThat(baseline.path("emailNotificationStatus").asText()).isEqualTo("NOT_TRIGGERED");
		verifyNoInteractions(controlledScenarioMailSender);
		JsonNode change = scenarioCommand(sessionId, owner.managerToken(), "next", baseline, "mail-change", 200);
		assertThat(change.at("/snapshot/stage").asText()).isEqualTo("CHANGE");
		assertThat(change.path("emailNotificationStatus").asText()).isEqualTo("NOT_TRIGGERED");
		verifyNoInteractions(controlledScenarioMailSender);
		assertThat(scenarioAi(sessionId, shipmentIds.get(0), owner.managerToken(), Map.of("scenarioShipmentKey", "A"))
				.getResponse().getStatus()).isEqualTo(400);
		mvc.perform(post(scenarioSessionPath(sessionId) + "/next")
				.header("Authorization", "Bearer " + otherTenant.managerToken())
				.header("Idempotency-Key", "unauthorized-mail-next").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("expectedEpochId", change.path("epochId").asText(),
						"expectedRevision", change.path("revision").asLong()))))
			.andExpect(status().isForbidden());
		verifyNoInteractions(controlledScenarioMailSender);

		JsonNode worsened = scenarioCommand(sessionId, owner.managerToken(), "next", change, "mail-worsened", 200);
		assertThat(worsened.at("/snapshot/stage").asText()).isEqualTo("WORSENED");
		assertThat(worsened.path("emailNotificationStatus").asText()).isIn("SUBMISSION_PENDING", "SUBMITTED");
		assertThat(scenarioCommand(sessionId, owner.managerToken(), "next", change, "mail-worsened", 200)).isEqualTo(worsened);
		assertThat(scenarioGet(sessionId, owner.managerToken()).path("emailNotificationStatus").asText()).isEqualTo("SUBMITTED");
		org.mockito.ArgumentCaptor<SimpleMailMessage> sent = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
		verify(controlledScenarioMailSender, times(1)).send(sent.capture());
		assertThat(sent.getValue().getTo()).containsExactly("demo@example.test");
		assertThat(sent.getValue().getSubject()).contains("SYNTHETIC DEMO").contains("NOT REAL");
		assertThat(sent.getValue().getText()).contains("Scenario A (CS203-DEMO-A)", "Backend-calculated connection risk: HIGH",
				"Remaining connection window: AVAILABLE — 4 hours (14400 seconds)", "EXCEEDS_DEADLINE", "20 hours",
				"Scenario B (CS203-DEMO-B)", "Backend-calculated connection risk: HIGH",
				"Remaining connection window: AVAILABLE — 4 hours (14400 seconds)", "NO_PREDICTED_IMPACT", "2 hours",
				"/freight-forwarder/shipments/" + shipmentIds.get(0), "/freight-forwarder/shipments/" + shipmentIds.get(1),
				"No real maritime disruption", "user-triggered AI assessment");
		assertThat(OFFLINE_CLIENT.inputs()).isEmpty();
	}

	@Test
	@Order(101)
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void disabledOrUnallowlistedSyntheticEmailDoesNotSendAndSmtpFailureKeepsWorsened() throws Exception {
		reset(controlledScenarioMailSender);
		Identity owner = createIdentity();
		List<Long> shipmentIds = createAndAuthorizeScenarioShipments(owner);
		configureControlledScenario(owner, shipmentIds);
		controlledScenarioEmailProperties.setEnabled(false);
		controlledScenarioEmailProperties.setRecipients("demo@example.test");
		UUID disabledSession = UUID.randomUUID();
		JsonNode disabledStart = scenarioCommand(disabledSession, owner.managerToken(), "start",
			scenarioCreate(disabledSession, owner.managerToken(), shipmentIds.get(0)), "disabled-start", 200);
		JsonNode disabledChange = scenarioCommand(disabledSession, owner.managerToken(), "next", disabledStart, "disabled-change", 200);
		JsonNode disabled = scenarioCommand(disabledSession, owner.managerToken(), "next", disabledChange, "disabled-worsened", 200);
		assertThat(disabled.path("emailNotificationStatus").asText()).isEqualTo("DISABLED");
		verifyNoInteractions(controlledScenarioMailSender);

		controlledScenarioEmailProperties.setEnabled(true);
		controlledScenarioEmailProperties.setRecipients("bad-address");
		UUID noRecipientsSession = UUID.randomUUID();
		JsonNode noRecipientsStart = scenarioCommand(noRecipientsSession, owner.managerToken(), "start",
			scenarioCreate(noRecipientsSession, owner.managerToken(), shipmentIds.get(0)), "no-recipient-start", 200);
		JsonNode noRecipientsChange = scenarioCommand(noRecipientsSession, owner.managerToken(), "next", noRecipientsStart, "no-recipient-change", 200);
		JsonNode noRecipients = scenarioCommand(noRecipientsSession, owner.managerToken(), "next", noRecipientsChange, "no-recipient-worsened", 200);
		assertThat(noRecipients.path("emailNotificationStatus").asText()).isEqualTo("NO_APPROVED_RECIPIENTS");
		verifyNoInteractions(controlledScenarioMailSender);

		controlledScenarioEmailProperties.setRecipients("demo@example.test");
		doThrow(new IllegalStateException("sensitive transport details must not be logged"))
				.when(controlledScenarioMailSender).send(any(SimpleMailMessage.class));
		UUID failureSession = UUID.randomUUID();
		JsonNode failureStart = scenarioCommand(failureSession, owner.managerToken(), "start",
			scenarioCreate(failureSession, owner.managerToken(), shipmentIds.get(0)), "failure-start", 200);
		JsonNode failureChange = scenarioCommand(failureSession, owner.managerToken(), "next", failureStart, "failure-change", 200);
		JsonNode failure = scenarioCommand(failureSession, owner.managerToken(), "next", failureChange, "failure-worsened", 200);
		assertThat(failure.at("/snapshot/stage").asText()).isEqualTo("WORSENED");
		assertThat(scenarioGet(failureSession, owner.managerToken()).path("emailNotificationStatus").asText()).isEqualTo("FAILED");
		assertThat(scenarioGet(failureSession, owner.managerToken()).at("/snapshot/stage").asText()).isEqualTo("WORSENED");
	}

	@Test
	@Order(-100)
	void authenticatedThreeStageReplayCalculatesRiskImpactAndGroundedAssessmentsWithoutOperationalMutation() throws Exception {
		OFFLINE_CLIENT.clear();
		SCENARIO_AI_CLIENT.clearScenarioInputs();
		TEST_CLOCK.set(Instant.parse("2026-10-01T00:00:00Z"));
		Identity identity = createIdentity();
		List<Long> shipmentIds = createAndAuthorizeScenarioShipments(identity);
		long sentinelId = createShipment(identity.managerToken(), new CreateShipmentRequest("CDG279-SENTINEL-" + shortId(),
				"Manila, PH", "Surabaya, ID", "Singapore", "CDG279 SENTINEL MOTHER", at("2026-10-01T06:00:00Z"),
				"CDG279 SENTINEL FEEDER", at("2026-10-02T12:00:00Z"), null, null, null, null, null, null));
		unauthenticatedRequestsAreRejected(shipmentIds.get(0));
		unrelatedImporterIsRejectedWithoutCallingTheClient(shipmentIds.get(0), identity.unrelatedToken());
		assertThat(OFFLINE_CLIENT.inputs()).isEmpty();
		assertThat(assessmentCount()).isZero();

		String baselineOperations = operationalSnapshot(sentinelId, shipmentIds);
		String baselineConstraints = constraintSnapshot(shipmentIds);
		long baselinePositionCount = positionCount();
		SyntheticDisruptionScenarioFixture.StageLedger ledger = SCENARIO.newLedger();
		for (int order = 0; order < SCENARIO.stages().size(); order++) {
			Stage stage = SCENARIO.stages().get(order);
			ExpectedStage expected = EXPECTED.get(order);
			TEST_CLOCK.set(stage.cutoff());
			assertThat(ledger.record(stage)).isEqualTo(SyntheticDisruptionScenarioFixture.RecordResult.RECORDED);
			positions.record(stage.mother().position());
			positions.record(stage.feeder().position());

			List<JsonNode> details = new ArrayList<>();
			for (int shipmentIndex = 0; shipmentIndex < shipmentIds.size(); shipmentIndex++) {
				long shipmentId = shipmentIds.get(shipmentIndex);
				JsonNode detail = detail(shipmentId, identity.managerToken());
				assertStageDetail(detail, stage, expected, shipmentIndex);
				details.add(detail);
				JsonNode context = context(shipmentId, identity.managerToken());
				assertContextStage(context, order);
			}
			assertThat(details.get(0).at("/arrivalComparison/estimatedArrivalAt"))
					.isEqualTo(details.get(1).at("/arrivalComparison/estimatedArrivalAt"));
			assertThat(details.get(0).at("/remainingConnectionWindow/totalSeconds"))
					.isEqualTo(details.get(1).at("/remainingConnectionWindow/totalSeconds"));
			assertRiskMatchesList(shipmentIds, identity.managerToken(), expected.riskLevel());

			for (int shipmentIndex = 0; shipmentIndex < shipmentIds.size(); shipmentIndex++) {
				MvcResult result = generate(shipmentIds.get(shipmentIndex), identity.managerToken()).andExpect(status().isOk())
						.andReturn();
				JsonNode assessment = json.readTree(result.getResponse().getContentAsString());
				if (order == 0) {
					assertThat(assessment.path("state").asText()).isEqualTo("ABSTAINED");
					assertThat(assessment.path("validationStatus").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
					assertThat(assessment.path("interpretation").isNull()).isTrue();
					assertThat(OFFLINE_CLIENT.inputs()).isEmpty();
				} else {
					assertThat(assessment.path("state").asText()).isEqualTo("GENERATED");
					assertThat(assessment.path("validationStatus").asText()).isEqualTo("VALID");
					assertThat(assessment.path("provider").asText()).isEqualTo("offline-synthetic-context-stub");
					assertThat(assessment.path("interpretation").path("claims").isArray()).isTrue();
					assertThat(assessment.path("interpretation").toString()).doesNotContain("cargo", "caused by", "reroute", "cancel");
				}
			}
			assertThat(assessmentCount()).isEqualTo(2L * (order + 1));
		}

		assertThat(ledger.latestRecordedOrder()).isEqualTo(2);
		assertThat(OFFLINE_CLIENT.inputs()).hasSize(4);
		assertOfflineInput(OFFLINE_CLIENT.inputs().get(0), "MEDIUM", 72_000, "EXCEEDS_DEADLINE", "cdg279-v1-revision-1");
		assertOfflineInput(OFFLINE_CLIENT.inputs().get(1), "MEDIUM", 72_000, "NO_PREDICTED_IMPACT", "cdg279-v1-revision-1");
		assertOfflineInput(OFFLINE_CLIENT.inputs().get(2), "HIGH", 14_400, "EXCEEDS_DEADLINE",
				"cdg279-v1-revision-1", "cdg279-v1-notice-2", "cdg279-v1-weather-2");
		assertOfflineInput(OFFLINE_CLIENT.inputs().get(3), "HIGH", 14_400, "NO_PREDICTED_IMPACT",
				"cdg279-v1-revision-1", "cdg279-v1-notice-2", "cdg279-v1-weather-2");
		assertThat(OFFLINE_CLIENT.inputs().get(2).evidence()).containsExactlyElementsOf(OFFLINE_CLIENT.inputs().get(3).evidence());
		assertThat(OFFLINE_CLIENT.inputs().get(2).evidence()).noneMatch(row -> row.evidenceId().contains("incompatible")
				|| row.evidenceId().contains("stale") || row.evidenceId().contains("unknown"));
		assertThat(OFFLINE_CLIENT.inputs().get(2).evidence()).extracting(MaritimeEvidence::evidenceId)
				.containsExactly("cdg279-v1-revision-1", "cdg279-v1-notice-2", "cdg279-v1-weather-2");
		assertThat(OFFLINE_CLIENT.inputs().get(0).shipmentContext().businessContext().comparison().outcome())
				.isEqualTo(com.drift.backend.shipment.DelayComparison.Outcome.EXCEEDS_DEADLINE);
		assertThat(OFFLINE_CLIENT.inputs().get(1).shipmentContext().businessContext().comparison().outcome())
				.isEqualTo(com.drift.backend.shipment.DelayComparison.Outcome.NO_PREDICTED_IMPACT);
		assertThat(OFFLINE_CLIENT.inputs().get(2).shipmentContext().connectionRisk().riskBasis())
				.isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE);
		assertThat(OFFLINE_CLIENT.inputs().get(2).shipmentContext().connectionRisk().arrivalEstimate().state())
				.isEqualTo(MaritimeAiConnectionRiskContext.EstimateState.AVAILABLE);
		assertThat(OFFLINE_CLIENT.inputs().get(0).evidence().getFirst().currentValue()).isEqualTo("2026-10-01T15:00:00Z");
		assertThat(OFFLINE_CLIENT.inputs().get(0).shipmentContext().connectionRisk().arrivalEstimate().estimatedArrivalAt().toInstant())
				.isEqualTo(Instant.parse("2026-10-01T16:00:00Z"));

		assertThat(operationalSnapshot(sentinelId, shipmentIds)).isEqualTo(baselineOperations);
		assertThat(constraintSnapshot(shipmentIds)).isEqualTo(baselineConstraints);
		assertThat(positionCount()).isEqualTo(baselinePositionCount + 6);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vessel_observations WHERE mmsi IN (?, ?)", Long.class,
				SyntheticDisruptionScenarioFixture.MOTHER_MMSI, SyntheticDisruptionScenarioFixture.FEEDER_MMSI)).isEqualTo(6L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recommendation_decisions", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_risk_transitions", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_risk_alert_deliveries", Long.class)).isZero();
		assertHistoricalSnapshots(shipmentIds, identity.managerToken());

		// An authorized importer can update only its own deadline; assessments retain the original facts.
		patchDeadline(shipmentIds.get(0), identity.managerToken(), "2026-10-02T07:00:00Z", PORT)
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(ShipmentConstraintEditForbiddenException.MESSAGE));
		patchDeadline(shipmentIds.get(0), identity.importerToken(), "2026-10-02T07:07:00Z", PORT).andExpect(status().isOk());
		JsonNode changedCurrentDetail = detail(shipmentIds.get(0), identity.managerToken());
		assertThat(changedCurrentDetail.at("/transshipmentArrivalDeadline/deadline").asText()).isEqualTo("2026-10-02T07:07:00Z");
		assertThat(changedCurrentDetail.at("/delayComparison/outcome").asText()).isEqualTo("EXCEEDS_DEADLINE");
		JsonNode historyBeforeReassignment = assessmentHistory(shipmentIds.get(0), identity.importerToken());
		assertHistoricalSnapshot(historyBeforeReassignment, "2026-10-01T04:00:00Z", "2026-10-01T12:00:00Z", "EXCEEDS_DEADLINE");

		// Reassignment correctly removes the former importer's current access. Existing history is
		// still shipment-scoped, so the newly linked importer can read old importer deadline snapshots;
		// the current schema has no historical importer-visibility boundary to enforce.
		createRelationship(identity.forwarderCompanyId(), identity.successorCompanyId(), identity.managerUserId());
		linkImporter(shipmentIds.get(0), identity.managerToken(), identity.successorCompanyId()).andExpect(status().isOk());
		JsonNode successorDetail = detail(shipmentIds.get(0), identity.successorToken());
		assertThat(successorDetail.path("transshipmentArrivalDeadline").isNull()).isTrue();
		int invocationCountBeforeDenied = OFFLINE_CLIENT.inputs().size();
		long assessmentRowsBeforeDenied = assessmentCount();
		for (long shipmentId : shipmentIds) {
			if (shipmentId == shipmentIds.get(0)) {
				mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + identity.importerToken()))
						.andExpect(status().isForbidden());
				mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-context").header("Authorization", "Bearer " + identity.importerToken()))
						.andExpect(status().isForbidden());
				generate(shipmentId, identity.importerToken()).andExpect(status().isForbidden());
				mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments")
						.header("Authorization", "Bearer " + identity.importerToken())).andExpect(status().isForbidden());
			} else {
				mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + identity.unrelatedToken()))
						.andExpect(status().isForbidden());
				mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-context").header("Authorization", "Bearer " + identity.unrelatedToken()))
						.andExpect(status().isForbidden());
				generate(shipmentId, identity.unrelatedToken()).andExpect(status().isForbidden());
				mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments")
						.header("Authorization", "Bearer " + identity.unrelatedToken())).andExpect(status().isForbidden());
			}
		}
		assertThat(OFFLINE_CLIENT.inputs()).hasSize(invocationCountBeforeDenied);
		assertThat(assessmentCount()).isEqualTo(assessmentRowsBeforeDenied);
		JsonNode successorHistory = assessmentHistory(shipmentIds.get(0), identity.successorToken());
		assertThat(successorHistory).hasSize(0);
		// CDG-289 keeps the previous importer's assessment history private after shipment reassignment.
		patchDeadline(shipmentIds.get(0), identity.importerToken(), "2026-10-02T06:00:00Z", PORT).andExpect(status().isForbidden());
		patchDeadline(shipmentIds.get(0), identity.successorToken(), "2026-10-02T06:00:00Z", PORT).andExpect(status().isOk());
		MvcResult successorAssessment = generate(shipmentIds.get(0), identity.successorToken()).andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("GENERATED"))
				.andExpect(jsonPath("$.validationStatus").value("VALID")).andReturn();
		assertThat(json.readTree(successorAssessment.getResponse().getContentAsString())
				.at("/deterministicSummary/businessContext/context/deadline/state").asText()).isEqualTo("RECORDED");
		assertThat(OFFLINE_CLIENT.inputs().getLast().shipmentContext().businessContext().deadline().instant().toInstant())
				.isEqualTo(Instant.parse("2026-10-02T06:00:00Z"));

		verifyExistingCalculationBoundaries(identity, shipmentIds.get(0));
	}

	@Test
	void authenticatedScenarioAiEndpointPassesTheSixCaseQualityMatrixAndRejectsStaleBindings() throws Exception {
		SCENARIO_AI_CLIENT.clearScenarioInputs();
		TEST_CLOCK.set(Instant.parse("2026-10-01T00:00:00Z"));
		Identity owner = createIdentity();
		Identity unrelated = createIdentity();
		List<Long> shipmentIds = createAndAuthorizeScenarioShipments(owner);
		configureControlledScenario(owner, shipmentIds);
		UUID sessionId = UUID.randomUUID();
		JsonNode created = scenarioCreate(sessionId, owner.managerToken(), shipmentIds.getFirst());
		JsonNode baseline = scenarioCommand(sessionId, owner.managerToken(), "start", created, "ai-quality-start", 200);
		assertThat(baseline.at("/snapshot/stage").asText()).isEqualTo("BASELINE");
		for (int shipmentIndex = 0; shipmentIndex < shipmentIds.size(); shipmentIndex++) {
			String key = shipmentIndex == 0 ? "A" : "B";
			assertThat(baseline.at("/snapshot/shipments/" + shipmentIndex + "/risk/level").asText())
					.isEqualTo("LOW");
			assertThat(baseline.at("/snapshot/shipments/" + shipmentIndex + "/businessImpact/outcome").asText())
					.isEqualTo("NO_PREDICTED_IMPACT");
			assertThat(baseline.at("/snapshot/shipments/" + shipmentIndex + "/scenarioShipmentKey").asText())
					.isEqualTo(key);
		}

		Map<String, Object> baselineARequest = scenarioAiRequest(baseline, "A");
		MvcResult unauthenticated = scenarioAi(sessionId, shipmentIds.getFirst(), null, baselineARequest);
		assertThat(unauthenticated.getResponse().getStatus()).isEqualTo(401);
		MvcResult unauthorized = scenarioAi(sessionId, shipmentIds.getFirst(), unrelated.unrelatedToken(), baselineARequest);
		assertThat(unauthorized.getResponse().getStatus()).isEqualTo(403);
		MvcResult unknownSession = scenarioAi(UUID.randomUUID(), shipmentIds.getFirst(), owner.managerToken(), baselineARequest);
		assertThat(unknownSession.getResponse().getStatus()).isEqualTo(404);
		assertThat(json.readTree(unknownSession.getResponse().getContentAsString()).path("code").asText())
				.isEqualTo("SCENARIO_NOT_FOUND");
		assertThat(SCENARIO_AI_CLIENT.scenarioInputs()).isEmpty();

		String beforeBaseline = scenarioOperationalStateSnapshot();
		long baselinePositions = positionCount();
		long baselineAssessments = assessmentCount();
		for (int shipmentIndex = 0; shipmentIndex < shipmentIds.size(); shipmentIndex++) {
			String key = shipmentIndex == 0 ? "A" : "B";
			MvcResult generated = scenarioAi(sessionId, shipmentIds.get(shipmentIndex), owner.managerToken(),
					scenarioAiRequest(baseline, key));
			assertThat(generated.getResponse().getStatus()).isEqualTo(200);
			JsonNode posted = json.readTree(generated.getResponse().getContentAsString());
			assertScenarioEnvelope(posted, sessionId, baseline, key, shipmentIds.get(shipmentIndex));
			assertThat(posted.at("/assessment/state").asText()).isEqualTo("ABSTAINED");
			assertThat(posted.at("/assessment/validationStatus").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
			assertThat(posted.at("/assessment/interpretation").isNull()).isTrue();
			String assessmentId = posted.at("/assessment/assessmentId").asText();
			assertThat(assessmentId).matches("[0-9a-f-]{36}");
			JsonNode retrieved = retrieveScenarioAssessment(sessionId, shipmentIds.get(shipmentIndex), assessmentId,
					owner.managerToken());
			assertThat(retrieved.path("assessment")).isEqualTo(posted.path("assessment"));
			assertScenarioEnvelope(retrieved, sessionId, baseline, key, shipmentIds.get(shipmentIndex));
		}
		assertThat(SCENARIO_AI_CLIENT.scenarioInputs()).isEmpty();
		assertThat(scenarioOperationalStateSnapshot()).isEqualTo(beforeBaseline);
		assertThat(positionCount()).isEqualTo(baselinePositions);
		assertThat(assessmentCount()).isEqualTo(baselineAssessments + 2);

		assertScenarioBindingRejected(sessionId, shipmentIds.getFirst(), baseline, "A", owner.managerToken(),
				"expectedEpochId", UUID.randomUUID().toString(), "STALE_EPOCH");
		assertScenarioBindingRejected(sessionId, shipmentIds.getFirst(), baseline, "A", owner.managerToken(),
				"expectedRevision", baseline.path("revision").asLong() + 1, "STALE_REVISION");
		assertScenarioBindingRejected(sessionId, shipmentIds.getFirst(), baseline, "A", owner.managerToken(),
				"expectedSnapshotId", UUID.randomUUID().toString(), "STALE_SNAPSHOT");
		assertScenarioBindingRejected(sessionId, shipmentIds.getFirst(), baseline, "A", owner.managerToken(),
				"expectedSimulationCutoffUtc", "2026-10-01T00:00:01Z", "STALE_SNAPSHOT");
		assertScenarioBindingRejected(sessionId, shipmentIds.get(1), baseline, "A", owner.managerToken(),
				null, null, "SCENARIO_SHIPMENT_MISMATCH");
		assertThat(SCENARIO_AI_CLIENT.scenarioInputs()).isEmpty();
		assertThat(assessmentCount()).isEqualTo(baselineAssessments + 2);

		int expectedStubCalls = 0;
		JsonNode current = baseline;
		for (int stageIndex = 1; stageIndex < EXPECTED.size(); stageIndex++) {
			current = scenarioCommand(sessionId, owner.managerToken(), "next", current,
					"ai-quality-next-" + stageIndex, 200);
			assertThat(current.at("/snapshot/stage").asText()).isEqualTo(stageIndex == 1 ? "CHANGE" : "WORSENED");
			String operationsBefore = scenarioOperationalStateSnapshot();
			long positionsBefore = positionCount();
			long assessmentsBefore = assessmentCount();
			ExpectedStage expected = EXPECTED.get(stageIndex);
			for (int shipmentIndex = 0; shipmentIndex < shipmentIds.size(); shipmentIndex++) {
				String key = shipmentIndex == 0 ? "A" : "B";
				MvcResult result = scenarioAi(sessionId, shipmentIds.get(shipmentIndex), owner.managerToken(),
						scenarioAiRequest(current, key));
				assertThat(result.getResponse().getStatus()).isEqualTo(200);
				JsonNode response = json.readTree(result.getResponse().getContentAsString());
				assertScenarioEnvelope(response, sessionId, current, key, shipmentIds.get(shipmentIndex));
				assertScenarioPositiveQuality(response, current, expected, shipmentIndex, key);
				String assessmentId = response.at("/assessment/assessmentId").asText();
				assertThat(assessmentId).matches("[0-9a-f-]{36}");
				JsonNode retrieved = retrieveScenarioAssessment(sessionId, shipmentIds.get(shipmentIndex), assessmentId,
						owner.managerToken());
				assertThat(retrieved.path("assessment")).isEqualTo(response.path("assessment"));
				assertScenarioEnvelope(retrieved, sessionId, current, key, shipmentIds.get(shipmentIndex));
				expectedStubCalls++;
				assertThat(SCENARIO_AI_CLIENT.scenarioInputs()).hasSize(expectedStubCalls);
				assertScenarioInput(SCENARIO_AI_CLIENT.scenarioInputs().get(expectedStubCalls - 1), current,
						expected, key);
			}
			assertThat(scenarioOperationalStateSnapshot()).isEqualTo(operationsBefore);
			assertThat(positionCount()).isEqualTo(positionsBefore);
			assertThat(assessmentCount()).isEqualTo(assessmentsBefore + 2);
		}
		assertThat(SCENARIO_AI_CLIENT.scenarioInputs()).hasSize(4);
		assertThat(assessmentCount()).isEqualTo(baselineAssessments + 6);
		assertThat(assessmentHistory(shipmentIds.getFirst(), owner.managerToken())).isEmpty();
		assertThat(assessmentHistory(shipmentIds.get(1), owner.managerToken())).isEmpty();
	}

	@Test
	void controlledScenarioApiIsAuthenticatedIsolatedReadOnlyAndDoesNotCallTheAiClient() throws Exception {
		Identity owner = createIdentity();
		Identity otherTenant = createIdentity();
		List<Long> shipmentIds = createAndAuthorizeScenarioShipments(owner);
		long sentinelId = createShipment(owner.managerToken(), new CreateShipmentRequest("CDG279-SCENARIO-SENTINEL-" + shortId(),
				"Manila, PH", "Surabaya, ID", "Singapore", "CDG279 SCENARIO SENTINEL MOTHER", at("2026-10-01T06:00:00Z"),
				"CDG279 SCENARIO SENTINEL FEEDER", at("2026-10-02T12:00:00Z"), null, null, null, null, null, null));
		UUID sessionId = UUID.randomUUID();
		UUID concurrentSessionId = UUID.randomUUID();
		OFFLINE_CLIENT.clear();
		String operationalBefore = scenarioOperationalSnapshot();
		long positionsBefore = positionCount();
		long assessmentsBefore = assessmentCount();
		String path = scenarioSessionPath(sessionId);
		String createBody = json.writeValueAsString(Map.of("anchorShipmentId", shipmentIds.get(0)));

		mvc.perform(put(path).contentType(MediaType.APPLICATION_JSON).content(createBody)).andExpect(status().isUnauthorized());
		mvc.perform(put(path).header("Authorization", "Bearer " + owner.managerToken())
				.contentType(MediaType.APPLICATION_JSON).content(createBody))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SCENARIO_DISABLED"));
		mvc.perform(put("/api/scenarios/cdg279/sessions/" + UUID.randomUUID())
				.header("Authorization", "Bearer " + owner.managerToken())).andExpect(status().isNotFound());

		configureControlledScenario(owner, shipmentIds);
		mvc.perform(put("/api/scenarios/cdg279/sessions/" + UUID.randomUUID())
				.header("Authorization", "Bearer " + owner.managerToken()))
				.andExpect(status().isNotFound());
		mvc.perform(put(path).header("Authorization", "Bearer " + otherTenant.managerToken())
				.contentType(MediaType.APPLICATION_JSON).content(createBody))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SCENARIO_ACCESS_DENIED"));
		JsonNode created = scenarioCreate(sessionId, owner.managerToken(), shipmentIds.get(0));
		assertThat(created.path("apiVersion").asText()).isEqualTo("1");
		assertThat(created.path("state").asText()).isEqualTo("UNSTARTED");
		assertThat(created.path("revision").asLong()).isZero();
		assertThat(created.path("snapshot").isNull()).isTrue();
		assertThat(created.at("/shipments/0/scenarioShipmentKey").asText()).isEqualTo("A");
		assertThat(created.at("/shipments/0/shipmentId").asLong()).isEqualTo(shipmentIds.get(0));
		assertThat(created.at("/shipments/1/scenarioShipmentKey").asText()).isEqualTo("B");
		assertThat(scenarioCreate(sessionId, owner.managerToken(), shipmentIds.get(0))).isEqualTo(created);
		Map<String, Object> startBody = Map.of("expectedEpochId", created.path("epochId").asText(),
				"expectedRevision", created.path("revision").asLong());
		mvc.perform(post(path + "/start").header("Authorization", "Bearer " + owner.managerToken())
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(startBody)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(post(path + "/start").header("Authorization", "Bearer " + owner.managerToken())
				.header("Idempotency-Key", "missing-revision")
				.contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("expectedEpochId", created.path("epochId").asText()))))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertThat(scenarioCommand(sessionId, owner.managerToken(), "next", created, "next-before-start", 409)
				.path("code").asText()).isEqualTo("SCENARIO_NOT_STARTED");
		mvc.perform(put(scenarioSessionPath(UUID.randomUUID())).header("Authorization", "Bearer " + owner.managerToken())
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("anchorShipmentId", sentinelId))))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SCENARIO_NOT_FOUND"));

		JsonNode baseline = scenarioCommand(sessionId, owner.managerToken(), "start", created, "start-key", 200);
		assertThat(baseline.path("state").asText()).isEqualTo("ACTIVE");
		assertThat(baseline.path("snapshot").path("stage").asText()).isEqualTo("BASELINE");
		assertThat(baseline.path("snapshot").path("simulationCutoffUtc").asText()).isEqualTo("2026-10-01T00:00:00Z");
		assertThat(baseline.path("snapshot").path("mapPlaybackCutoffUtc").asText())
				.isEqualTo(baseline.path("snapshot").path("simulationCutoffUtc").asText());
		assertThat(baseline.at("/snapshot/maritimeContext/evidence")).isEmpty();
		assertThat(baseline.at("/snapshot/shipments/0/risk/level").asText()).isEqualTo("LOW");
		assertThat(baseline.at("/snapshot/shipments/0/businessImpact/outcome").asText()).isEqualTo("NO_PREDICTED_IMPACT");
		assertThat(baseline.at("/snapshot/shipments/0/id").isMissingNode()).isTrue();
		assertThat(baseline.at("/snapshot/aisObservations/0/sourceMode").asText()).isEqualTo("SYNTHETIC_DEMONSTRATION");
		assertThat(baseline.at("/snapshot/aisObservations/0/position/source").asText()).isEqualTo("SEEDED");
		assertThat(scenarioGet(sessionId, owner.managerToken())).isEqualTo(baseline);

		JsonNode concurrentUnstarted = scenarioCreate(concurrentSessionId, owner.managerToken(), shipmentIds.get(1));
		assertThat(concurrentUnstarted.path("epochId").asText()).isNotEqualTo(baseline.path("epochId").asText());
		assertThat(scenarioGet(concurrentSessionId, owner.managerToken()).path("state").asText()).isEqualTo("UNSTARTED");

		JsonNode change = scenarioCommand(sessionId, owner.managerToken(), "next", baseline, "next-1", 200);
		assertThat(change.path("snapshot").path("stage").asText()).isEqualTo("CHANGE");
		assertThat(change.path("snapshot").path("simulationCutoffUtc").asText()).isEqualTo("2026-10-01T04:00:00Z");
		assertThat(change.at("/snapshot/shipments/0/risk/level").asText()).isEqualTo("MEDIUM");
		assertThat(change.at("/snapshot/shipments/0/businessImpact/outcome").asText()).isEqualTo("EXCEEDS_DEADLINE");
		assertThat(change.at("/snapshot/maritimeContext/evidence")).hasSize(1);
		assertThat(change.at("/snapshot/maritimeContext/evidence/0/evidenceId").asText()).isEqualTo("cdg279-v1-revision-1");
		assertThat(change.at("/snapshot/maritimeContext/evidence").toString())
				.doesNotContain("cdg279-v1-notice-2", "cdg279-v1-weather-2", "negative-2");
		assertThat(scenarioGet(sessionId, owner.managerToken())).isEqualTo(change);
		assertThat(scenarioCommand(sessionId, owner.managerToken(), "next", baseline, "next-1", 200)).isEqualTo(change);

		JsonNode worsened = scenarioCommand(sessionId, owner.managerToken(), "next", change, "next-2", 200);
		assertThat(worsened.path("state").asText()).isEqualTo("COMPLETE");
		assertThat(worsened.path("snapshot").path("stage").asText()).isEqualTo("WORSENED");
		assertThat(worsened.path("snapshot").path("simulationCutoffUtc").asText()).isEqualTo("2026-10-01T08:00:00Z");
		assertThat(worsened.at("/snapshot/shipments/0/risk/level").asText()).isEqualTo("HIGH");
		assertThat(worsened.at("/snapshot/shipments/1/risk/level").asText()).isEqualTo("HIGH");
		assertThat(worsened.at("/snapshot/maritimeContext/assessmentCutoffUtc").asText())
				.isEqualTo(worsened.at("/snapshot/simulationCutoffUtc").asText());
		JsonNode worsenedEvidence = worsened.at("/snapshot/maritimeContext/evidence");
		assertThat(worsenedEvidence).hasSize(6);
		assertThat(worsenedEvidence.findValues("evidenceId").stream().map(JsonNode::asText).toList())
				.containsExactly("cdg279-v1-revision-1", "cdg279-v1-notice-2", "cdg279-v1-weather-2",
						"cdg279-v1-notice-other-area-2", "cdg279-v1-stale-negative-2", "cdg279-v1-unknown-negative-2");
		assertThat(worsenedEvidence.get(3).path("compatibilityState").asText()).isEqualTo("INCOMPATIBLE");
		assertThat(worsenedEvidence.get(4).path("freshness").asText()).isEqualTo("STALE");
		assertThat(worsenedEvidence.get(5).path("freshness").asText()).isEqualTo("UNKNOWN");
		assertThat(scenarioCommand(sessionId, owner.managerToken(), "next", worsened, "next-final", 409)
				.path("code").asText()).isEqualTo("SCENARIO_COMPLETE");
		mvc.perform(get(path).header("Authorization", "Bearer " + otherTenant.managerToken()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SCENARIO_ACCESS_DENIED"));

		JsonNode reset = scenarioCommand(sessionId, owner.managerToken(), "reset", worsened, "reset-key", 200);
		assertThat(reset.path("state").asText()).isEqualTo("UNSTARTED");
		assertThat(reset.path("snapshot").isNull()).isTrue();
		assertThat(reset.path("epochId").asText()).isNotEqualTo(worsened.path("epochId").asText());
		assertThat(scenarioCommand(sessionId, owner.managerToken(), "next", baseline, "next-1", 200)).isEqualTo(change);
		assertThat(scenarioGet(sessionId, owner.managerToken())).isEqualTo(reset);
		assertThat(scenarioCommand(sessionId, owner.managerToken(), "next", baseline, "old-epoch-new-key", 409)
				.path("code").asText()).isEqualTo("STALE_EPOCH");
		assertThat(scenarioCommand(sessionId, owner.managerToken(), "reset", worsened, "reset-key", 200)).isEqualTo(reset);
		assertThat(scenarioCommand(sessionId, owner.managerToken(), "reset", reset, "reset-again", 200)).isEqualTo(reset);
		JsonNode newBaseline = scenarioCommand(sessionId, owner.managerToken(), "start", reset, "start-after-reset", 200);
		assertThat(newBaseline.at("/snapshot/snapshotId").asText())
				.isNotEqualTo(baseline.at("/snapshot/snapshotId").asText());

		assertThat(scenarioOperationalSnapshot()).isEqualTo(operationalBefore);
		assertThat(positionCount()).isEqualTo(positionsBefore);
		assertThat(assessmentCount()).isEqualTo(assessmentsBefore);
		assertThat(OFFLINE_CLIENT.inputs()).isEmpty();
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void scenarioSessionLifecycleWorksWithoutATestManagedTransaction() throws Exception {
		assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		OFFLINE_CLIENT.clear();
		Identity owner = createIdentity();
		Identity otherTenant = createIdentity();
		List<Long> shipmentIds = createAndAuthorizeScenarioShipments(owner);
		configureControlledScenario(owner, shipmentIds);
		String operationalBefore = scenarioOperationalSnapshot();
		long positionsBefore = positionCount();
		long assessmentsBefore = assessmentCount();

		UUID readOnlySessionId = UUID.randomUUID();
		TransactionTemplate readOnlyTransaction = new TransactionTemplate(transactionManager);
		readOnlyTransaction.setReadOnly(true);
		MvcResult readOnlyFailure = readOnlyTransaction.execute(status -> {
			status.setRollbackOnly();
			try {
				return mvc.perform(put(scenarioSessionPath(readOnlySessionId))
						.header("Authorization", "Bearer " + owner.managerToken())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Map.of("anchorShipmentId", shipmentIds.get(0)))))
					.andReturn();
			} catch (Exception exception) {
				throw new AssertionError(exception);
			}
		});
		assertThat(readOnlyFailure).isNotNull();
		assertThat(readOnlyFailure.getResponse().getStatus()).isEqualTo(503);
		assertThat(json.readTree(readOnlyFailure.getResponse().getContentAsString()).path("code").asText())
				.isEqualTo("SCENARIO_STORAGE_UNAVAILABLE");
		JsonNode recoveredAfterStorageFailure = scenarioCreate(readOnlySessionId, owner.managerToken(), shipmentIds.get(0));
		assertThat(recoveredAfterStorageFailure.path("state").asText()).isEqualTo("UNSTARTED");

		UUID sessionId = UUID.randomUUID();

		JsonNode created = scenarioCreate(sessionId, owner.managerToken(), shipmentIds.get(0));
		assertThat(created.path("state").asText()).isEqualTo("UNSTARTED");
		assertThat(created.path("revision").asLong()).isZero();
		assertThat(created.path("snapshot").isNull()).isTrue();
		assertThat(created.at("/shipments/0/scenarioShipmentKey").asText()).isEqualTo("A");
		assertThat(created.at("/shipments/1/scenarioShipmentKey").asText()).isEqualTo("B");
		assertThat(scenarioGet(sessionId, owner.managerToken())).isEqualTo(created);
		mvc.perform(get(scenarioSessionPath(sessionId)).header("Authorization", "Bearer " + otherTenant.managerToken()))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SCENARIO_ACCESS_DENIED"));

		JsonNode baseline = scenarioCommand(sessionId, owner.managerToken(), "start", created, "outside-tx-start", 200);
		assertThat(baseline.at("/snapshot/stage").asText()).isEqualTo("BASELINE");
		JsonNode change = scenarioCommand(sessionId, owner.managerToken(), "next", baseline, "outside-tx-next-1", 200);
		assertThat(change.at("/snapshot/stage").asText()).isEqualTo("CHANGE");
		assertThat(scenarioCommand(sessionId, owner.managerToken(), "next", baseline, "outside-tx-stale-revision", 409)
				.path("code").asText()).isEqualTo("STALE_REVISION");
		JsonNode worsened = scenarioCommand(sessionId, owner.managerToken(), "next", change, "outside-tx-next-2", 200);
		assertThat(worsened.at("/snapshot/stage").asText()).isEqualTo("WORSENED");
		JsonNode reset = scenarioCommand(sessionId, owner.managerToken(), "reset", worsened, "outside-tx-reset", 200);
		assertThat(reset.path("state").asText()).isEqualTo("UNSTARTED");
		assertThat(reset.path("snapshot").isNull()).isTrue();
		assertThat(reset.path("epochId").asText()).isNotEqualTo(worsened.path("epochId").asText());
		assertThat(scenarioCommand(sessionId, owner.managerToken(), "next", worsened, "outside-tx-stale-epoch", 409)
				.path("code").asText()).isEqualTo("STALE_EPOCH");
		assertThat(scenarioGet(sessionId, owner.managerToken())).isEqualTo(reset);

		assertThat(scenarioOperationalSnapshot()).isEqualTo(operationalBefore);
		assertThat(positionCount()).isEqualTo(positionsBefore);
		assertThat(assessmentCount()).isEqualTo(assessmentsBefore);
		assertThat(OFFLINE_CLIENT.inputs()).isEmpty();
	}

	private void assertStageDetail(JsonNode detail, Stage stage, ExpectedStage expected, int shipmentIndex) {
		assertThat(detail.at("/connectionWindow/totalSeconds").asLong()).isEqualTo(108_000L);
		assertThat(detail.at("/arrivalComparison/estimatedArrivalAt").asText()).isEqualTo(expected.eta());
		assertThat(detail.at("/arrivalComparison/lastReceivedAt").asText()).isEqualTo(stage.cutoff().toString());
		assertThat(detail.at("/arrivalComparison/possiblyOutdated").asBoolean()).isFalse();
		assertThat(detail.at("/remainingConnectionWindow/status").asText()).isEqualTo("AVAILABLE");
		assertThat(detail.at("/remainingConnectionWindow/totalSeconds").asLong()).isEqualTo(expected.remainingSeconds());
		assertThat(detail.at("/risk/level").asText()).isEqualTo(expected.riskLevel());
		assertThat(detail.at("/riskContributions/assessed").asBoolean()).isTrue();
		assertThat(detail.at("/riskContributions/contributions/0/value").asText()).isEqualTo("Remaining connection window");
		String expectedOutcome = shipmentIndex == 0 ? expected.impactA() : expected.impactB();
		assertThat(detail.at("/delayComparison/outcome").asText()).isEqualTo(expectedOutcome);
		assertThat(detail.at("/transshipmentArrivalDeadline/transshipmentPort").asText()).isEqualTo(PORT);
		String expectedDeadline = shipmentIndex == 0 ? "2026-10-01T12:00:00Z" : "2026-10-02T10:00:00Z";
		assertThat(detail.at("/transshipmentArrivalDeadline/deadline").asText()).isEqualTo(expectedDeadline);
		assertThat(detail.at("/plannedMotherArrivalAt").asText()).isEqualTo("2026-10-01T06:00:00Z");
		assertThat(detail.at("/plannedFeederDepartureAt").asText()).isEqualTo("2026-10-02T12:00:00Z");
	}

	private void assertContextStage(JsonNode context, int order) {
		if (order == 0) {
			assertThat(context.path("sourceMode").asText()).isEqualTo("UNAVAILABLE");
			assertThat(context.path("shipmentAssociationConfidence").asText()).isEqualTo("UNAVAILABLE");
		} else {
			assertThat(context.path("sourceMode").asText()).isEqualTo("SYNTHETIC_DEMONSTRATION");
			assertThat(context.path("shipmentAssociationConfidence").asText()).isEqualTo("VERIFIED");
		}
		List<String> ids = new ArrayList<>();
		context.path("evidence").forEach(row -> ids.add(row.path("evidenceId").asText()));
		if (order == 0) assertThat(ids).isEmpty();
		if (order == 1) assertThat(ids).containsExactly("cdg279-v1-revision-1");
		if (order == 2) assertThat(ids).containsExactly("cdg279-v1-revision-1", "cdg279-v1-notice-2", "cdg279-v1-weather-2",
				"cdg279-v1-notice-other-area-2", "cdg279-v1-stale-negative-2", "cdg279-v1-unknown-negative-2");
		context.path("evidence").forEach(row -> {
			if (!row.path("observedAt").isNull()) assertThat(Instant.parse(row.path("observedAt").asText()))
					.isBefore(Instant.parse(context.path("assessmentCutoffUtc").asText()).plusNanos(1));
			assertThat(Instant.parse(row.path("retrievedAtUtc").asText()))
					.isBefore(Instant.parse(context.path("assessmentCutoffUtc").asText()).plusNanos(1));
		});
	}

	private void assertRiskMatchesList(List<Long> ids, String token, String expectedRisk) throws Exception {
		JsonNode list = json.readTree(mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
		for (long id : ids) {
			JsonNode listed = null;
			for (JsonNode item : list) if (item.path("id").asLong() == id) listed = item;
			assertThat(listed).isNotNull();
			assertThat(listed.at("/risk/level").asText()).isEqualTo(expectedRisk);
		}
	}

	private void assertOfflineInput(MaritimeAiEvidencePackage input, String riskLevel, long remaining,
			String outcome, String... evidenceIds) {
		assertThat(input.shipmentAssociationConfidence()).isEqualTo("VERIFIED");
		assertThat(input.evidence()).extracting(MaritimeEvidence::evidenceId).containsExactly(evidenceIds);
		assertThat(input.shipmentContext().connectionRisk().riskLevel().name()).isEqualTo(riskLevel);
		assertThat(input.shipmentContext().connectionRisk().remainingWindow().remainingSeconds()).isEqualTo(remaining);
		assertThat(input.shipmentContext().connectionRisk().riskBasis())
				.isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE);
		assertThat(input.shipmentContext().businessContext().comparison().outcome().name()).isEqualTo(outcome);
	}

	private void assertHistoricalSnapshots(List<Long> shipmentIds, String token) throws Exception {
		JsonNode historyA = assessmentHistory(shipmentIds.get(0), token);
		JsonNode historyB = assessmentHistory(shipmentIds.get(1), token);
		assertThat(historyA).hasSize(3);
		assertThat(historyB).hasSize(3);
		assertThat(historyA.toString()).contains("ABSTAINED", "INSUFFICIENT_EVIDENCE", "VALID", "2026-10-01T12:00:00Z");
		assertHistoricalSnapshot(historyA, "2026-10-01T04:00:00Z", "2026-10-01T12:00:00Z", "EXCEEDS_DEADLINE");
		assertHistoricalSnapshot(historyB, "2026-10-01T04:00:00Z", "2026-10-02T10:00:00Z", "NO_PREDICTED_IMPACT");
		assertHistoricalSnapshot(historyA, "2026-10-01T08:00:00Z", "2026-10-01T12:00:00Z", "EXCEEDS_DEADLINE");
		assertHistoricalSnapshot(historyB, "2026-10-01T08:00:00Z", "2026-10-02T10:00:00Z", "NO_PREDICTED_IMPACT");
	}

	private void assertHistoricalSnapshot(JsonNode history, String cutoff, String deadline, String outcome) {
		JsonNode snapshot = null;
		for (JsonNode item : history) {
			if (cutoff.equals(item.path("assessmentCutoffUtc").asText())) snapshot = item;
		}
		assertThat(snapshot).isNotNull();
		assertThat(snapshot.path("state").asText()).isEqualTo("GENERATED");
		assertThat(snapshot.path("validationStatus").asText()).isEqualTo("VALID");
		assertThat(snapshot.at("/deterministicSummary/connectionRisk/selectedWindowBasis").asText())
				.isEqualTo("AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE");
		assertThat(snapshot.at("/deterministicSummary/businessContext/context/deadline/instant").asText()).isEqualTo(deadline);
		assertThat(snapshot.at("/deterministicSummary/businessContext/context/comparison/outcome").asText()).isEqualTo(outcome);
		assertThat(snapshot.at("/deterministicSummary/evidenceFacts/0/evidenceId").asText()).isEqualTo("cdg279-v1-revision-1");
	}

	private void unauthenticatedRequestsAreRejected(long shipmentId) throws Exception {
		mvc.perform(get("/api/shipments/" + shipmentId)).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-context")).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/shipments/" + shipmentId + "/maritime-ai-assessments")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments")).andExpect(status().isUnauthorized());
	}

	private void unrelatedImporterIsRejectedWithoutCallingTheClient(long shipmentId, String token) throws Exception {
		mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
		mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-context").header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
		generate(shipmentId, token).andExpect(status().isForbidden());
		mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments")
				.header("Authorization", "Bearer " + token)).andExpect(status().isForbidden());
	}

	private void verifyExistingCalculationBoundaries(Identity identity, long scenarioShipmentId) throws Exception {
		TEST_CLOCK.set(Instant.parse("2026-10-03T00:00:00Z"));
		long noPosition = createBoundaryShipment(identity, "NO-POSITION", "Synthetic Multi-source Port", "990279101");
		long missingSpeed = createBoundaryShipment(identity, "MISSING-SPEED", PORT, "990279102");
		long lowSpeed = createBoundaryShipment(identity, "LOW-SPEED", PORT, "990279103");
		long unknownPort = createBoundaryShipment(identity, "UNKNOWN-PORT", "CDG279 unknown port", "990279104");
		long noDeadline = createBoundaryShipment(identity, "NO-DEADLINE", PORT, "990279105");
		long mismatchedDeadline = createBoundaryShipment(identity, "MISMATCHED-DEADLINE", PORT, "990279106");
		long sixHourBoundary = createBoundaryShipment(identity, "SIX-HOUR-BOUNDARY", PORT, "990279107");
		for (long id : List.of(noPosition, missingSpeed, lowSpeed, unknownPort, noDeadline, mismatchedDeadline, sixHourBoundary)) {
			linkImporter(id, identity.managerToken(), identity.importerCompanyId()).andExpect(status().isOk());
		}
		positions.record(boundaryPosition("990279102", "CDG279 BOUNDARY MISSING-SPEED", null, TEST_CLOCK.instant(), null));
		positions.record(boundaryPosition("990279103", "CDG279 BOUNDARY LOW-SPEED", new BigDecimal("0.499"), TEST_CLOCK.instant(), null));
		positions.record(boundaryPosition("990279104", "CDG279 BOUNDARY UNKNOWN-PORT", new BigDecimal("10"), TEST_CLOCK.instant(), null));
		positions.record(boundaryPosition("990279105", "CDG279 BOUNDARY NO-DEADLINE", new BigDecimal("10"), TEST_CLOCK.instant(), null));
		positions.record(boundaryPosition("990279106", "CDG279 BOUNDARY MISMATCHED-DEADLINE", new BigDecimal("10"), TEST_CLOCK.instant(), null));
		positions.record(boundaryPosition("990279107", "CDG279 BOUNDARY SIX-HOUR-BOUNDARY", new BigDecimal("10"), TEST_CLOCK.instant(), null));
		patchDeadline(mismatchedDeadline, identity.importerToken(), "2026-10-04T12:00:00Z", PORT).andExpect(status().isOk());
		replaceBoundaryPort(mismatchedDeadline, identity.managerToken(), "Other Synthetic Port");

		assertThat(detail(noPosition, identity.managerToken()).at("/arrivalComparison/unavailableReason/code").asText()).isEqualTo("NO_POSITION");
		assertPlannedFallback(detail(noPosition, identity.managerToken()));
		assertThat(detail(missingSpeed, identity.managerToken()).at("/arrivalComparison/unavailableReason/code").asText()).isEqualTo("UNUSABLE_SPEED");
		assertPlannedFallback(detail(missingSpeed, identity.managerToken()));
		assertThat(detail(lowSpeed, identity.managerToken()).at("/arrivalComparison/unavailableReason/code").asText()).isEqualTo("UNUSABLE_SPEED");
		assertPlannedFallback(detail(lowSpeed, identity.managerToken()));
		assertThat(detail(unknownPort, identity.managerToken()).at("/arrivalComparison/unavailableReason/code").asText()).isEqualTo("UNKNOWN_PORT");
		assertPlannedFallback(detail(unknownPort, identity.managerToken()));
		assertThat(detail(noDeadline, identity.managerToken()).at("/delayComparison/reason").asText()).isEqualTo("MISSING_CONSTRAINT");
		assertThat(detail(mismatchedDeadline, identity.managerToken()).at("/transshipmentArrivalDeadline/applicable").asBoolean()).isFalse();
		assertThat(detail(mismatchedDeadline, identity.managerToken()).at("/delayComparison/reason").asText()).isEqualTo("INCOMPATIBLE_MILESTONE");

		JsonNode exact = detail(sixHourBoundary, identity.managerToken());
		assertThat(exact.at("/arrivalComparison/possiblyOutdated").asBoolean()).isFalse();
		assertThat(exact.at("/remainingConnectionWindow/status").asText()).isEqualTo("AVAILABLE");
		TEST_CLOCK.set(Instant.parse("2026-10-03T06:00:00.000001Z"));
		JsonNode stale = detail(sixHourBoundary, identity.managerToken());
		assertThat(stale.at("/arrivalComparison/possiblyOutdated").asBoolean()).isTrue();
		assertThat(stale.at("/remainingConnectionWindow").isNull()).isTrue();
		assertThat(stale.at("/riskContributions/contributions/0/value").asText()).isEqualTo("Planned connection window");
		assertThat(stale.at("/connectionWindow/totalSeconds").asLong()).isEqualTo(108_000L);
		assertThat(detail(scenarioShipmentId, identity.managerToken()).at("/plannedFeederDepartureAt").asText())
				.isEqualTo("2026-10-02T12:00:00Z");
	}

	private void assertPlannedFallback(JsonNode detail) {
		assertThat(detail.at("/connectionWindow/totalSeconds").asLong()).isEqualTo(108_000L);
		assertThat(detail.at("/remainingConnectionWindow").isNull()).isTrue();
		assertThat(detail.at("/riskContributions/contributions/0/value").asText()).isEqualTo("Planned connection window");
	}

	private AisPosition boundaryPosition(String mmsi, String name, BigDecimal speed, Instant ingestedAt, String port) {
		return new AisPosition(mmsi, name, new BigDecimal("2.26"), new BigDecimal("103.82"), speed,
				new BigDecimal("180"), 180, 0, true, 0, ingestedAt, AisPositionSource.SEEDED);
	}

	private long createBoundaryShipment(Identity identity, String suffix, String port, String mmsi) throws Exception {
		String motherName = "CDG279 BOUNDARY " + suffix;
		String feederName = "CDG279 BOUNDARY FEEDER " + suffix;
		CreateShipmentRequest request = new CreateShipmentRequest("CDG279-BND-" + suffix + "-" + shortId(), "Origin", "Destination",
				port, motherName, at("2026-10-03T12:00:00Z"), feederName, at("2026-10-04T18:00:00Z"), null, mmsi,
				null, null, "990279201", null);
		return createShipment(identity.managerToken(), request);
	}

	private void replaceBoundaryPort(long shipmentId, String token, String port) throws Exception {
		String reference = jdbc.queryForObject("SELECT shipment_reference FROM shipments WHERE id = ?", String.class, shipmentId);
		long version = jdbc.queryForObject("SELECT version FROM shipments WHERE id = ?", Long.class, shipmentId);
		UpdateShipmentRequest request = new UpdateShipmentRequest(reference, "Origin", "Destination", port,
				"CDG279 BOUNDARY MISMATCHED-DEADLINE", at("2026-10-03T12:00:00Z"),
				"CDG279 BOUNDARY FEEDER MISMATCHED-DEADLINE", at("2026-10-04T18:00:00Z"), null, "990279106", null,
				null, "990279201", null, version);
		mvc.perform(put("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
				.andExpect(status().isOk());
	}

	private List<Long> createAndAuthorizeScenarioShipments(Identity identity) throws Exception {
		createRelationship(identity.forwarderCompanyId(), identity.importerCompanyId(), identity.managerUserId());
		List<Long> ids = new ArrayList<>();
		for (SyntheticDisruptionScenarioFixture.ShipmentInput input : SCENARIO.shipments()) {
			long shipmentId = createShipment(identity.managerToken(), input.shipment());
			linkImporter(shipmentId, identity.managerToken(), identity.importerCompanyId()).andExpect(status().isOk());
			patchDeadline(shipmentId, identity.importerToken(), input.deadline().toString(), input.expectedTransshipmentPort())
					.andExpect(status().isOk());
			ids.add(shipmentId);
		}
		return List.copyOf(ids);
	}

	private Identity createIdentity() throws Exception {
		String suffix = shortId();
		long forwarder = createCompany("CDG279_FWD_" + suffix);
		long importer = createCompany("CDG279_IMP_" + suffix);
		long successor = createCompany("CDG279_NEXT_" + suffix);
		long unrelated = createCompany("CDG279_OTHER_" + suffix);
		String managerEmail = createAccount(forwarder, "FREIGHT_FORWARDER", "manager");
		long managerUserId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, managerEmail);
		String importerEmail = createAccount(importer, "IMPORTER", "importer");
		String successorEmail = createAccount(successor, "IMPORTER", "successor");
		String unrelatedEmail = createAccount(unrelated, "IMPORTER", "unrelated");
		return new Identity(forwarder, importer, successor, managerUserId, login(managerEmail), login(importerEmail),
				login(successorEmail), login(unrelatedEmail));
	}

	private long createShipment(String token, CreateShipmentRequest request) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
				.andExpect(status().isCreated()).andReturn();
		return json.readTree(result.getResponse().getContentAsString()).path("id").asLong();
	}

	private void createRelationship(long forwarderId, long importerId, long actorId) {
		jdbc.update("""
				INSERT INTO forwarder_importer_relationships
				(forwarder_company_id, importer_company_id, active, created_at, created_by_user_id, updated_at, updated_by_user_id)
				VALUES (?, ?, TRUE, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP, ?)
				""", forwarderId, importerId, actorId, actorId);
	}

	private org.springframework.test.web.servlet.ResultActions linkImporter(long shipmentId, String token, long importerId) throws Exception {
		return mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("importerCompanyId", importerId))));
	}

	private org.springframework.test.web.servlet.ResultActions patchDeadline(long shipmentId, String token, String deadline, String port) throws Exception {
		return mvc.perform(patch("/api/shipments/" + shipmentId + "/transshipment-arrival-deadline")
				.header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(Map.of("transshipmentArrivalDeadline", deadline, "expectedTransshipmentPort", port))));
	}

	private JsonNode detail(long shipmentId, String token) throws Exception {
		return json.readTree(mvc.perform(get("/api/shipments/" + shipmentId).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private String scenarioSessionPath(UUID sessionId) {
		return "/api/scenarios/v1/sessions/" + sessionId;
	}

	private void configureControlledScenario(Identity identity, List<Long> shipmentIds) {
		controlledScenarioProperties.setEnabled(true);
		controlledScenarioProperties.setDemoCompanyId(identity.forwarderCompanyId());
		controlledScenarioProperties.setAllowedUserIds(Set.of(identity.managerUserId()));
		controlledScenarioProperties.setShipmentAId(shipmentIds.get(0));
		controlledScenarioProperties.setShipmentBId(shipmentIds.get(1));
	}

	private JsonNode scenarioCreate(UUID sessionId, String token, long anchorShipmentId) throws Exception {
		return json.readTree(mvc.perform(put(scenarioSessionPath(sessionId)).header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("anchorShipmentId", anchorShipmentId))))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private JsonNode scenarioGet(UUID sessionId, String token) throws Exception {
		return json.readTree(mvc.perform(get(scenarioSessionPath(sessionId)).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private JsonNode scenarioCommand(UUID sessionId, String token, String action, JsonNode expected,
			String idempotencyKey, int expectedStatus) throws Exception {
		Map<String, Object> body = Map.of("expectedEpochId", expected.path("epochId").asText(),
				"expectedRevision", expected.path("revision").asLong());
		return json.readTree(mvc.perform(post(scenarioSessionPath(sessionId) + "/" + action)
				.header("Authorization", "Bearer " + token).header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
				.andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString());
	}

	private Map<String, Object> scenarioAiRequest(JsonNode session, String key) {
		JsonNode snapshot = session.path("snapshot");
		return new java.util.LinkedHashMap<>(Map.of("scenarioShipmentKey", key,
				"expectedEpochId", session.path("epochId").asText(),
				"expectedRevision", session.path("revision").asLong(),
				"expectedSnapshotId", snapshot.path("snapshotId").asText(),
				"expectedSimulationCutoffUtc", snapshot.path("simulationCutoffUtc").asText()));
	}

	private MvcResult scenarioAi(UUID sessionId, long shipmentId, String token, Map<String, Object> body) throws Exception {
		var request = post(scenarioSessionPath(sessionId) + "/shipments/" + shipmentId + "/interpretation")
				.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
		if (token != null) request.header("Authorization", "Bearer " + token);
		return mvc.perform(request).andReturn();
	}

	private JsonNode retrieveScenarioAssessment(UUID sessionId, long shipmentId, String assessmentId, String token)
			throws Exception {
		return json.readTree(mvc.perform(get("/api/scenarios/v1/sessions/" + sessionId + "/shipments/" + shipmentId
				+ "/assessments/" + assessmentId).header("Authorization", "Bearer " + token))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private void assertScenarioBindingRejected(UUID sessionId, long shipmentId, JsonNode session, String key,
			String token, String changedField, Object changedValue, String expectedCode) throws Exception {
		Map<String, Object> body = scenarioAiRequest(session, key);
		if (changedField != null) body.put(changedField, changedValue);
		MvcResult result = scenarioAi(sessionId, shipmentId, token, body);
		assertThat(result.getResponse().getStatus()).isEqualTo(409);
		assertThat(json.readTree(result.getResponse().getContentAsString()).path("code").asText()).isEqualTo(expectedCode);
	}

	private void assertScenarioEnvelope(JsonNode response, UUID sessionId, JsonNode session, String key, long shipmentId) {
		assertThat(response.path("sessionId").asText()).isEqualTo(sessionId.toString());
		assertThat(response.path("epochId").asText()).isEqualTo(session.path("epochId").asText());
		assertThat(response.path("snapshotId").asText()).isEqualTo(session.at("/snapshot/snapshotId").asText());
		assertThat(response.path("revision").asLong()).isEqualTo(session.path("revision").asLong());
		assertThat(response.path("simulationCutoffUtc").asText()).isEqualTo(session.at("/snapshot/simulationCutoffUtc").asText());
		assertThat(response.path("scenarioShipmentKey").asText()).isEqualTo(key);
		assertThat(response.path("shipmentId").asLong()).isEqualTo(shipmentId);
		assertThat(response.at("/assessment/bundleVersion").asText()).isEqualTo("cdg246.maritime-evidence-bundle.v1");
		assertThat(response.at("/assessment/bundleHash").asText()).matches("[0-9a-f]{64}");
		assertThat(response.at("/assessment/assessmentCutoffUtc").asText())
				.isEqualTo(session.at("/snapshot/simulationCutoffUtc").asText());
	}

	private void assertScenarioPositiveQuality(JsonNode response, JsonNode session, ExpectedStage expected,
			int shipmentIndex, String key) {
		JsonNode assessment = response.path("assessment");
		assertThat(assessment.path("state").asText()).isEqualTo("GENERATED");
		assertThat(assessment.path("validationStatus").asText()).isEqualTo("VALID");
		assertThat(assessment.path("provider").asText()).isEqualTo("offline-scenario-quality-stub");
		assertThat(assessment.path("model").asText()).isEqualTo("deterministic-fixture");
		assertThat(assessment.path("promptVersion").asText())
				.isEqualTo("cdg290.scenario-grounded-v1.7.2");
		JsonNode claims = assessment.at("/interpretation/claims");
		JsonNode riskClaim = claimWithRelationship(claims, "RISK_BUFFER_REVIEW");
		JsonNode businessClaim = claimWithRelationship(claims, "DELAY_COMPARISON_REVIEW");
		assertThat(riskClaim).isNotNull();
		assertThat(businessClaim).isNotNull();
		long businessClaimCount = 0;
		for (JsonNode claim : claims) {
			if ("DELAY_COMPARISON_REVIEW".equals(claim.path("relationshipType").asText())) businessClaimCount++;
		}
		assertThat(businessClaimCount).isEqualTo(1);
		assertThat(riskClaim.path("statement").asText().toLowerCase()).contains("synthetic", "revision", "warrants human review",
				expected.riskLevel().toLowerCase(), "ais", "remaining", "available");
		JsonNode riskReview = riskClaim.path("riskReview");
		assertThat(riskReview.path("severity").asText()).isEqualTo(expected.riskLevel());
		assertThat(riskReview.path("selectedWindowBasis").asText()).isEqualTo("AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE");
		assertThat(riskReview.path("selectedWindowState").asText()).isEqualTo("AVAILABLE");
		assertThat(riskReview.path("estimateFreshness").asText()).isEqualTo("AVAILABLE");
		List<String> riskReferences = strings(riskClaim.path("factReferences"));
		assertThat(riskReferences).contains("evidence:cdg279-v1-revision-1", "risk:CONNECTION_SEVERITY",
				"risk:REMAINING_CONNECTION_WINDOW", "risk:AIS_ESTIMATED_ARRIVAL", "risk:ARRIVAL_ESTIMATE_FRESHNESS")
				.doesNotContain("risk:PLANNED_CONNECTION_WINDOW", "risk:ARRIVAL_ESTIMATE_NOT_USED");
		assertThat(strings(riskClaim.path("evidenceIds"))).containsExactly("cdg279-v1-revision-1");

		String expectedOutcome = shipmentIndex == 0 ? expected.impactA() : expected.impactB();
		String businessStatement = businessClaim.path("statement").asText().toLowerCase(Locale.ROOT);
		String deadline = key.equals("A") ? "2026-10-01T12:00:00Z" : "2026-10-02T10:00:00Z";
		long marginHours = Math.abs(java.time.Duration.between(java.time.Instant.parse(deadline),
				java.time.Instant.parse(expected.eta())).toHours());
		assertThat(businessStatement).contains("shipment " + key.toLowerCase(Locale.ROOT), marginHours + " hours");
		if ("EXCEEDS_DEADLINE".equals(expectedOutcome))
			assertThat(businessStatement).contains("predicted arrival exceeds the applicable transshipment-arrival deadline by "
					+ marginHours + " hours");
		else assertThat(businessStatement).contains("predicted arrival is " + marginHours
				+ " hours before the applicable transshipment-arrival deadline");
		assertThat(businessStatement).contains("synthetic", "expected-arrival revision", "transshipment-arrival deadline", "applicable");
		List<String> businessReferences = strings(businessClaim.path("factReferences"));
		assertThat(businessReferences).contains("evidence:cdg279-v1-revision-1", "business:TRANSSHIPMENT_ARRIVAL_DEADLINE",
				"business:TRANSSHIPMENT_ARRIVAL_DEADLINE_PORT", "business:TRANSSHIPMENT_ARRIVAL_DEADLINE_APPLICABILITY",
				"comparison:DELAY_OUTCOME", "comparison:ESTIMATED_ARRIVAL", "comparison:RECORDED_DEADLINE",
				"comparison:RECORDED_TRANSSHIPMENT_PORT");
		assertThat(strings(businessClaim.path("evidenceIds"))).containsExactly("cdg279-v1-revision-1");
		JsonNode deterministicBusinessImpact = session.at("/snapshot/shipments/" + shipmentIndex + "/businessImpact");
		assertThat(deterministicBusinessImpact.path("outcome").asText()).isEqualTo(expectedOutcome);
		assertThat(deterministicBusinessImpact.path("reason").isNull()).isTrue();
		assertThat(deterministicBusinessImpact.path("deadline").asText()).isNotBlank();
		assertThat(deterministicBusinessImpact.path("recordedTransshipmentPort").asText()).isNotBlank();

		List<String> followUps = strings(assessment.at("/interpretation/followUpConsiderations")).stream()
				.map(String::toLowerCase).toList();
		assertThat(followUps).anySatisfy(text -> assertThat(text).contains("carrier operations team", "verify", "arrival estimate"));
		assertThat(followUps).anySatisfy(text -> assertThat(text).contains("operations team", "confirm", "transshipment-arrival deadline", "current shipment port"));
		String authored = assessment.path("interpretation").toString().toLowerCase();
		assertThat(authored).doesNotContain("cargo", "financial loss", "caused", "missed connection", "urgent", "probability", "reroute", "cancel");
		assertThat(assessment.at("/interpretation/summary").asText().toLowerCase())
				.doesNotContain("deadline", "exceeds_deadline", "no predicted impact");
		assertThat(assessment.at("/interpretation/uncertainties").toString().toLowerCase())
				.doesNotContain("deadline", "exceeds_deadline", "no predicted impact");
		for (JsonNode claim : claims) {
			if ("DELAY_COMPARISON_REVIEW".equals(claim.path("relationshipType").asText())) continue;
			assertThat(claim.path("statement").asText().toLowerCase())
					.doesNotContain("deadline", "exceeds_deadline", "no predicted impact");
		}

		// Both rubric points come from the authored claim: shipment-specific backend margin and human verification.
		int usefulnessScore = 0;
		if (businessStatement.contains("shipment " + key.toLowerCase(Locale.ROOT))
				&& businessStatement.contains(marginHours + " hours")
				&& (("EXCEEDS_DEADLINE".equals(expectedOutcome) && businessStatement.contains("predicted arrival exceeds")
				|| "NO_PREDICTED_IMPACT".equals(expectedOutcome) && businessStatement.contains("predicted arrival is " + marginHours + " hours before")))
				&& businessReferences.contains("comparison:DELAY_OUTCOME")
				&& businessReferences.contains("business:TRANSSHIPMENT_ARRIVAL_DEADLINE_APPLICABILITY")) usefulnessScore++;
		if (followUps.stream().anyMatch(text -> text.contains("carrier operations team") && text.contains("verify") && text.contains("arrival estimate"))
				&& followUps.stream().anyMatch(text -> text.contains("operations team") && text.contains("confirm")
						&& text.contains("transshipment-arrival deadline") && text.contains("current shipment port"))) usefulnessScore++;
		assertThat(usefulnessScore).as("scenario shipment %s usefulness out of two", key).isEqualTo(2);
	}

	private void assertScenarioInput(MaritimeAiEvidencePackage input, JsonNode session, ExpectedStage expected, String key) {
		assertThat(input.shipmentAssociationConfidence()).isEqualTo("VERIFIED");
		assertThat(input.assessmentCutoffUtc()).isEqualTo(session.at("/snapshot/simulationCutoffUtc").asText());
		assertThat(input.shipmentContext().scenario().shipmentKey()).isEqualTo(key);
		assertThat(input.shipmentContext().scenario().stage()).isEqualTo(session.at("/snapshot/stage").asText());
		assertThat(input.shipmentContext().scenario().simulationCutoffUtc()).isEqualTo(input.assessmentCutoffUtc());
		assertThat(input.shipmentContext().connectionRisk().riskLevel().name()).isEqualTo(expected.riskLevel());
		assertThat(input.shipmentContext().connectionRisk().riskBasis())
				.isEqualTo(MaritimeAiConnectionRiskContext.RiskBasis.AIS_STRAIGHT_LINE_ARRIVAL_ESTIMATE);
		assertThat(input.shipmentContext().connectionRisk().remainingWindow().status()).isEqualTo("AVAILABLE");
		assertThat(input.shipmentContext().connectionRisk().arrivalEstimate().state())
				.isEqualTo(MaritimeAiConnectionRiskContext.EstimateState.AVAILABLE);
		assertThat(input.shipmentContext().businessContext().comparison().outcome().name())
				.isEqualTo(key.equals("A") ? expected.impactA() : expected.impactB());
		assertThat(input.evidence()).extracting(MaritimeEvidence::evidenceId).contains("cdg279-v1-revision-1");
		assertThat(input.evidence()).allMatch(item -> "SYNTHETIC_DEMONSTRATION".equals(item.sourceMode()));
	}

	private static JsonNode claimWithRelationship(JsonNode claims, String relationship) {
		for (JsonNode claim : claims) if (relationship.equals(claim.path("relationshipType").asText())) return claim;
		return null;
	}

	private static List<String> strings(JsonNode array) {
		List<String> result = new ArrayList<>();
		array.forEach(value -> result.add(value.asText()));
		return result;
	}

	private JsonNode context(long shipmentId, String token) throws Exception {
		return json.readTree(mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-context")
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private JsonNode assessmentHistory(long shipmentId, String token) throws Exception {
		return json.readTree(mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments")
				.header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
	}

	private org.springframework.test.web.servlet.ResultActions generate(long shipmentId, String token) throws Exception {
		return mvc.perform(post("/api/shipments/" + shipmentId + "/maritime-ai-assessments")
				.header("Authorization", "Bearer " + token));
	}

	private String operationalSnapshot(long sentinelId, List<Long> shipmentIds) {
		return jsonRows("SELECT to_jsonb(s)::text FROM shipments s WHERE id IN (?, ?, ?) ORDER BY id",
				new Object[] { shipmentIds.get(0), shipmentIds.get(1), sentinelId })
				+ jsonRows("SELECT to_jsonb(s)::text FROM shipment_risk_states s WHERE shipment_id IN (?, ?, ?) ORDER BY shipment_id",
						new Object[] { shipmentIds.get(0), shipmentIds.get(1), sentinelId })
				+ jsonRows("SELECT to_jsonb(t)::text FROM shipment_risk_transitions t WHERE shipment_id IN (?, ?, ?) ORDER BY id",
						new Object[] { shipmentIds.get(0), shipmentIds.get(1), sentinelId })
				+ jsonRows("SELECT to_jsonb(d)::text FROM shipment_risk_alert_deliveries d JOIN shipment_risk_transitions t ON t.id=d.transition_id WHERE t.shipment_id IN (?, ?, ?) ORDER BY d.id",
						new Object[] { shipmentIds.get(0), shipmentIds.get(1), sentinelId })
				+ jsonRows("SELECT to_jsonb(d)::text FROM recommendation_decisions d WHERE shipment_id IN (?, ?, ?) ORDER BY shipment_id",
						new Object[] { shipmentIds.get(0), shipmentIds.get(1), sentinelId })
				+ jsonRows("SELECT to_jsonb(a)::text FROM shipment_risk_alert_activation a ORDER BY id", new Object[0]);
	}

	private String scenarioOperationalSnapshot() {
		return scenarioOperationalStateSnapshot()
				+ jsonRows("SELECT to_jsonb(a)::text FROM maritime_ai_assessments a ORDER BY id", new Object[0]);
	}

	private String scenarioOperationalStateSnapshot() {
		return jsonRows("SELECT to_jsonb(s)::text FROM shipments s ORDER BY id", new Object[0])
				+ jsonRows("SELECT to_jsonb(c)::text FROM shipment_importer_constraints c ORDER BY shipment_id", new Object[0])
				+ jsonRows("SELECT to_jsonb(e)::text FROM shipment_change_events e ORDER BY id", new Object[0])
				+ jsonRows("SELECT to_jsonb(c)::text FROM shipment_field_changes c ORDER BY event_id, id", new Object[0])
				+ jsonRows("SELECT to_jsonb(s)::text FROM shipment_risk_states s ORDER BY shipment_id", new Object[0])
				+ jsonRows("SELECT to_jsonb(s)::text FROM shipment_risk_transitions s ORDER BY id", new Object[0])
				+ jsonRows("SELECT to_jsonb(d)::text FROM shipment_risk_alert_deliveries d ORDER BY id", new Object[0])
				+ jsonRows("SELECT to_jsonb(d)::text FROM recommendation_decisions d ORDER BY shipment_id", new Object[0])
				+ jsonRows("SELECT to_jsonb(a)::text FROM shipment_risk_alert_activation a ORDER BY id", new Object[0]);
	}

	private String constraintSnapshot(List<Long> shipmentIds) {
		return jsonRows("SELECT to_jsonb(c)::text FROM shipment_importer_constraints c WHERE shipment_id IN (?, ?) ORDER BY shipment_id",
				new Object[] { shipmentIds.get(0), shipmentIds.get(1) });
	}

	private String jsonRows(String sql, Object[] args) { return jdbc.queryForList(sql, String.class, args).toString(); }
	private long assessmentCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments", Long.class); }
	private long positionCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM vessel_observations", Long.class); }

	private long createCompany(String code) {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, "CDG-279 " + code);
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private String createAccount(long companyId, String role, String tag) {
		String email = "cdg279-" + tag + "-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ? FROM roles WHERE code = ?
				""", "CDG-279 " + tag, email, passwords.encode(PASSWORD), companyId, role);
		return email;
	}

	private String login(String email) throws Exception {
		String body = json.writeValueAsString(Map.of("email", email, "password", PASSWORD));
		return JsonPath.read(mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.token");
	}

	private static java.time.OffsetDateTime at(String instant) { return java.time.OffsetDateTime.parse(instant); }
	private static String shortId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 10); }

	private record Identity(long forwarderCompanyId, long importerCompanyId, long successorCompanyId, long managerUserId,
			String managerToken, String importerToken, String successorToken, String unrelatedToken) { }
	private record ExpectedStage(String eta, long remainingSeconds, String riskLevel, String impactA, String impactB) { }

	@TestConfiguration
	static class ScenarioConfiguration {
		@Bean @Primary Clock syntheticDisruptionClock() { return TEST_CLOCK; }
		@Bean @Primary JavaMailSender controlledScenarioTestMailSender() { return mock(JavaMailSender.class); }
		@Bean @Primary com.drift.backend.maritimecontext.MaritimeContextService syntheticDisruptionContext() {
			return SCENARIO.contextForClock(TEST_CLOCK);
		}
		@Bean @Primary ScenarioQualityStubClient scenarioQualityStubClient() { return SCENARIO_AI_CLIENT; }
	}

	private static final class MutableScenarioClock extends Clock {
		private final AtomicReference<Instant> current;
		private MutableScenarioClock(Instant initial) { current = new AtomicReference<>(initial); }
		void set(Instant value) { current.set(value); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) {
			if (!ZoneOffset.UTC.equals(zone)) throw new UnsupportedOperationException("The fixture clock is UTC only.");
			return this;
		}
		@Override public Instant instant() { return current.get(); }
	}
}
