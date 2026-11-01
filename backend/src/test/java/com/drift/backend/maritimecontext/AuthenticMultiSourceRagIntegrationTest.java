package com.drift.backend.maritimecontext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.maritimeai.MaritimeAiClaim;
import com.drift.backend.maritimeai.MaritimeAiClient;
import com.drift.backend.maritimeai.MaritimeAiEvidencePackage;
import com.drift.backend.maritimeai.MaritimeAiInterpretation;
import com.drift.backend.maritimeai.MaritimeAiSyntheticMultiSourceIntegrationTests.SyntheticInterpretationCaptureClient;
import com.drift.backend.shipment.ShipmentDetailResponse;
import com.drift.backend.shipment.ShipmentStatus;
import com.jayway.jsonpath.JsonPath;

import tools.jackson.databind.ObjectMapper;

/** Local-only CDG-249 proof that authentic curated evidence reaches the CDG-247 client boundary. */
@SpringBootTest(properties = { "app.maritime-ai.enabled=true", "app.maritime-context.demo-enabled=true",
		"app.maritime-context.curated-snapshots-enabled=true",
		"app.maritime-ai.authentic-retrospective-inference-approved=true",
		"app.maritime-ai.authentic-retrospective-source-types-approved=MPA_ETA_REVISION,NEA_WEATHER",
		"app.maritime-ai.authentic-retrospective-evidence-ids-approved=mpa-expected-revision-9351048-20261026T154603,nea-rainfall-20261027T153306",
		"app.maritime-ai.controlled-replay-stub-enabled=false" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthenticMultiSourceRagIntegrationTest.Config.class)
@Transactional
class AuthenticMultiSourceRagIntegrationTest {
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired CapturingClient client;
	@Value("${cdg257.authentic-smoke.capture-interpretation:false}") boolean captureInterpretation;
	@Value("${cdg257.authentic-smoke.capture-path:}") String capturePath;
	private String token;
	private Long shipmentId;

	@BeforeEach
	void setUp() throws Exception {
		client.last.set(null);
		Long companyId = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, "HARBOURLINE_DEMO");
		String email = "cdg249-" + UUID.randomUUID() + "@example.com";
		jdbc.update("INSERT INTO users (full_name, email, password_hash, role_id, company_id) "
				+ "SELECT ?, ?, ?, id, ? FROM roles WHERE code = 'FREIGHT_FORWARDER'",
				"CDG-249 verification user", email, passwords.encode("Example123"), companyId);
		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk()).andReturn();
		token = JsonPath.read(login.getResponse().getContentAsString(), "$.token");
		MvcResult created = mvc.perform(post("/api/shipments").header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON).content(shipmentJson()))
				.andExpect(status().isCreated()).andReturn();
		shipmentId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
	}

	@Test
	void sendsAuthenticMpaAndNeaFieldsToClientThenPersistsAndReturnsAuthorizedAssessment() throws Exception {
		String beforeArrival = jdbc.queryForObject("SELECT planned_mother_arrival_at::text FROM shipments WHERE id = ?", String.class, shipmentId);
		String beforeDeparture = jdbc.queryForObject("SELECT planned_feeder_departure_at::text FROM shipments WHERE id = ?", String.class, shipmentId);
		Long beforeRiskTransitions = jdbc.queryForObject("SELECT COUNT(*) FROM shipment_risk_transitions WHERE shipment_id = ?", Long.class, shipmentId);
		mvc.perform(post("/api/shipments/" + shipmentId + "/maritime-ai-assessments")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk()).andExpect(jsonPath("$.state").value("GENERATED"))
			.andExpect(jsonPath("$.validationStatus").value("VALID"));

		MaritimeAiEvidencePackage input = client.last.get();
		assertThat(input).isNotNull();
		assertThat(input.shipmentAssociationConfidence()).isEqualTo("UNVERIFIED_IMO_ONLY");
		assertThat(input.evidence()).extracting(MaritimeEvidence::sourceType)
			.containsExactlyInAnyOrder("MPA_ETA_REVISION", "NEA_WEATHER");
		assertThat(input.evidence()).allSatisfy(evidence -> {
			assertThat(evidence.sourceMode()).isEqualTo("RETROSPECTIVE_ANALYSIS");
			assertThat(evidence.citation()).isNotBlank();
			assertThat(evidence.retrievedAtUtc()).isNotBlank();
			assertThat(evidence.shipmentAssociationConfidence()).isEqualTo("UNVERIFIED_IMO_ONLY");
		});
		assertThat(input.evidence()).filteredOn(e -> "MPA_ETA_REVISION".equals(e.sourceType())).singleElement()
			.satisfies(e -> {
				assertThat(e.evidenceId()).isEqualTo("mpa-expected-revision-9351048-20261026T154603");
				assertThat(e.sourceIdentifier()).isEqualTo("MPA OCEANS-X Vessels Due to Arrive");
				assertThat(e.sourceEventAt()).isEqualTo("2026-10-29T02:00:00+08:00");
				assertThat(e.changeMagnitude()).isEqualTo("+180 minutes");
				assertThat(e.limitation()).contains("does not verify a shipment booking", "delay cause");
			});
		assertThat(input.evidence()).filteredOn(e -> "NEA_WEATHER".equals(e.sourceType())).singleElement()
			.satisfies(e -> {
				assertThat(e.evidenceId()).isEqualTo("nea-rainfall-20261027T153306");
				assertThat(e.sourceIdentifier()).isEqualTo("NEA rainfall station observations");
				assertThat(e.observedAt()).isEqualTo("2026-10-27T23:25:00+08:00");
				assertThat(e.locationCompatibility()).isEqualTo("SINGAPORE_PORT_CONTEXT_ONLY");
				assertThat(e.limitation()).contains("Regional Singapore station context only", "causation");
			});

		mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments")
				.header("Authorization", "Bearer " + token))
			.andExpect(status().isOk()).andExpect(jsonPath("$[0].state").value("GENERATED"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE shipment_id = ?", Long.class, shipmentId))
			.isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT planned_mother_arrival_at::text FROM shipments WHERE id = ?", String.class, shipmentId)).isEqualTo(beforeArrival);
		assertThat(jdbc.queryForObject("SELECT planned_feeder_departure_at::text FROM shipments WHERE id = ?", String.class, shipmentId)).isEqualTo(beforeDeparture);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_risk_transitions WHERE shipment_id = ?", Long.class, shipmentId))
			.isEqualTo(beforeRiskTransitions);
		if (captureInterpretation) {
			Path output = Config.capturePath(capturePath);
			assertThat(Files.isRegularFile(output)).isTrue();
			assertThat(Files.getPosixFilePermissions(output)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		}
	}

	@Test
	void selectsBothMpaOnlyNeaOnlyOrNeitherWithoutChangingEvidenceContract() {
		var shipment = shipment("9351048");
		var actual = new CuratedMaritimeEvidenceAdapter(true, new ObjectMapper())
			.forShipment(shipment, Instant.parse("2026-10-27T16:00:00Z"));
		assertThat(packageFor(shipment, actual).evidence()).hasSize(2);
		assertThat(packageFor(shipment, actual.stream().filter(e -> "MPA_ETA_REVISION".equals(e.sourceType())).toList()).evidence()).hasSize(1);
		assertThat(packageFor(shipment, actual.stream().filter(e -> "NEA_WEATHER".equals(e.sourceType())).toList()).evidence()).hasSize(1);
		assertThat(packageFor(shipment, List.of()).evidence()).isEmpty();
	}

	@Test
	void authenticRealClientModeCannotStartWithoutTheGlobalExternalApprovalGate() {
		assertThatThrownBy(() -> new Config().authenticClient(new CapturingClient(), input -> MaritimeAiClient.MaritimeAiClientResult.unavailable("unused"),
				true, false, "", false, new ObjectMapper()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true");
	}

	private static MaritimeAiEvidencePackage packageFor(ShipmentDetailResponse shipment, List<MaritimeEvidence> evidence) {
		var context = new MaritimeContextService(true, List.<MaritimeEvidenceSourceAdapter>of((ignored, cutoff) -> evidence),
				Clock.fixed(Instant.parse("2026-10-27T16:00:00Z"), ZoneOffset.UTC));
		var bundle = context.forShipment(shipment);
		return new MaritimeAiEvidencePackage(bundle.contractVersion(), bundle.assessmentCutoffUtc(),
				bundle.shipmentAssociationConfidence(), bundle.evidence());
	}

	private static ShipmentDetailResponse shipment(String imo) {
		return new ShipmentDetailResponse(1L, 1L, "CDG249", "SG", "ID", "Singapore", "KOTA LARIS",
				java.time.OffsetDateTime.parse("2026-10-29T02:00:00+08:00"), imo, null, null, null, null, null, null, null, Instant.EPOCH, 0L, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null, ShipmentStatus.ACTIVE);
	}

	private static String shipmentJson() {
		return ("{\"shipmentReference\":\"CDG249-%s\",\"origin\":\"Synthetic origin\",\"destination\":\"Synthetic destination\","
				+ "\"transshipmentPort\":\"Singapore\",\"motherVessel\":\"KOTA LARIS\",\"motherVesselImo\":\"9351048\","
				+ "\"plannedMotherArrivalAt\":\"2026-10-29T02:00:00+08:00\",\"feederVessel\":\"Synthetic feeder\","
				+ "\"plannedFeederDepartureAt\":\"2026-10-29T10:00:00+08:00\"}").formatted(UUID.randomUUID());
	}

	@TestConfiguration
	static class Config {
		@Bean @Primary MaritimeContextService authenticContext(ObjectMapper json) {
			return new MaritimeContextService(true, List.of(new CuratedMaritimeEvidenceAdapter(true, json)),
					Clock.fixed(Instant.parse("2026-10-27T16:00:00Z"), ZoneOffset.UTC));
		}
		@Bean CapturingClient capturingClient() { return new CapturingClient(); }
		@Bean @Primary MaritimeAiClient authenticClient(CapturingClient capturingClient,
				@Qualifier("configuredMaritimeAiClient") MaritimeAiClient configuredClient,
				@Value("${cdg257.authentic-smoke.use-real-client:false}") boolean useRealClient,
				@Value("${cdg257.authentic-smoke.capture-interpretation:false}") boolean captureInterpretation,
				@Value("${cdg257.authentic-smoke.capture-path:}") String capturePath,
				@Value("${app.maritime-ai.external-requests-approved:false}") boolean externalRequestsApproved,
				ObjectMapper json) {
			MaritimeAiClient delegate;
			if (useRealClient) {
				if (!externalRequestsApproved) {
					throw new IllegalStateException("Authentic real smoke test requires MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true");
				}
				if (!"APPROVED".equals(System.getenv("CDG257_RUN_REAL_AUTHENTIC_SMOKE"))) {
					throw new IllegalStateException("Authentic real smoke test requires CDG257_RUN_REAL_AUTHENTIC_SMOKE=APPROVED");
				}
				delegate = input -> {
					capturingClient.last.set(input);
					return configuredClient.interpret(input);
				};
			} else {
				delegate = capturingClient;
			}
			return captureInterpretation ? new SyntheticInterpretationCaptureClient(delegate, json, capturePath(capturePath),
					Set.of("mpa-expected-revision-9351048-20261026T154603", "nea-rainfall-20261027T153306"),
					Set.of("RETROSPECTIVE_ANALYSIS"), "cdg257.authentic-retrospective.capture.v1") : delegate;
		}

		static Path capturePath(String configured) {
			if (configured != null && !configured.isBlank()) return Path.of(configured);
			return Path.of(System.getProperty("user.dir"), "target", "cdg257-authentic-retrospective-interpretation-real-response.json");
		}
	}

	static class CapturingClient implements MaritimeAiClient {
		final AtomicReference<MaritimeAiEvidencePackage> last = new AtomicReference<>();
		@Override public MaritimeAiClientResult interpret(MaritimeAiEvidencePackage input) {
			last.set(input);
			return new MaritimeAiClientResult("capturing-stub", "local", "cdg249.local.v1",
					new MaritimeAiInterpretation("Historical evidence is available.", List.of(
							new MaritimeAiClaim("Review the documented retrospective expected-arrival revision against the schedule context.",
									List.of("mpa-expected-revision-9351048-20261026T154603"), "UNCERTAIN", "MOTHER", "EXPECTED_ARRIVAL",
									"VESSEL_OBSERVATION", List.of("evidence:mpa-expected-revision-9351048-20261026T154603",
											"schedule:MOTHER:PLANNED_ARRIVAL", "schedule:FEEDER:PLANNED_DEPARTURE"), "SCHEDULE_REVIEW"),
							new MaritimeAiClaim("The regional rainfall observation is context only; it does not establish a vessel-specific effect.",
									List.of("nea-rainfall-20261027T153306"), "CONTEXTUAL", null, null, "REGIONAL_CONTEXT",
									List.of("evidence:nea-rainfall-20261027T153306"), "REGIONAL_WEATHER_CONTEXT")),
							List.of("The evidence remains vessel and regional context only; no causation or shipment-specific outcome is asserted."), List.of(), true, null), null);
		}
	}
}
