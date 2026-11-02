package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.maritimeai.MaritimeAiAssessmentResponse;
import com.drift.backend.maritimeai.MaritimeAiBusinessContext;
import com.drift.backend.maritimeai.MaritimeAiConnectionRiskContext;
import com.drift.backend.maritimeai.MaritimeAiEvidencePackage;
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
		"app.maritime-ai.enabled=true",
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
class SyntheticDisruptionScenarioIntegrationTests {
	private static final Scenario SCENARIO = SyntheticDisruptionScenarioFixture.create("Integration1");
	private static final MutableScenarioClock TEST_CLOCK = new MutableScenarioClock(Instant.parse("2026-10-01T00:00:00Z"));
	private static final SyntheticDisruptionAiClient OFFLINE_CLIENT = new SyntheticDisruptionAiClient();
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

	@Test
	void authenticatedThreeStageReplayCalculatesRiskImpactAndGroundedAssessmentsWithoutOperationalMutation() throws Exception {
		OFFLINE_CLIENT.clear();
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
		assertHistoricalSnapshot(successorHistory, "2026-10-01T04:00:00Z", "2026-10-01T12:00:00Z", "EXCEEDS_DEADLINE");
		// This current-owner history behavior is documented as a pre-existing limitation; no migration
		// or production authorization change belongs to the CDG-283/284 test-scope implementation.
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
		@Bean @Primary com.drift.backend.maritimecontext.MaritimeContextService syntheticDisruptionContext() {
			return SCENARIO.contextForClock(TEST_CLOCK);
		}
		@Bean @Primary SyntheticDisruptionAiClient syntheticDisruptionAiClient() { return OFFLINE_CLIENT; }
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
