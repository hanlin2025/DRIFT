package com.drift.backend.maritimeai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.maritimecontext.MaritimeContextService;
import com.drift.backend.maritimecontext.SyntheticMultiSourceMaritimeFixture;
import com.jayway.jsonpath.JsonPath;

/**
 * Proves that the CDG-246 assembly service, CDG-247 provider boundary, validation, persistence,
 * and authorized retrieval work together with three independently supplied fictional sources.
 */
@SpringBootTest(properties = { "app.maritime-ai.enabled=true", "app.maritime-context.demo-enabled=false" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(MaritimeAiSyntheticMultiSourceIntegrationTests.MultiSourceConfiguration.class)
@Transactional
class MaritimeAiSyntheticMultiSourceIntegrationTests {
	private static final String HISTORICAL_CAPTURE_FILE = "cdg247-synthetic-multisource-interpretation-original-real-response.json";
	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired MaritimeAiClient client;
	private String token;
	private Long shipmentId;

	@BeforeEach
	void setUp() throws Exception {
		Long companyId = jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, "HARBOURLINE_DEMO");
		String email = "cdg249-multisource-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ? FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", "CDG-249 synthetic user", email, passwords.encode("Example123"), companyId);
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
	void passesMpaNoticeAndNeaContentThroughTheProviderBoundaryThenPersistsAuthorizedAssessment() throws Exception {
		String beforeArrival = jdbc.queryForObject("SELECT planned_mother_arrival_at::text FROM shipments WHERE id = ?", String.class, shipmentId);
		String beforeDeparture = jdbc.queryForObject("SELECT planned_feeder_departure_at::text FROM shipments WHERE id = ?", String.class, shipmentId);
		mvc.perform(post("/api/shipments/" + shipmentId + "/maritime-ai-assessments").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("GENERATED"))
				.andExpect(jsonPath("$.validationStatus").value("VALID"))
				.andExpect(jsonPath("$.interpretation.claims[0].evidenceIds").isArray())
				.andExpect(jsonPath("$.interpretation.claims[0].factReferences[0]").value("evidence:" + SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID))
				.andExpect(jsonPath("$.deterministicSummary.summaryVersion").value("cdg247.deterministic-facts.v2.canonical-references"))
				.andExpect(jsonPath("$.deterministicSummary.shipmentScheduleFacts[0].eventType").value("PLANNED_ARRIVAL"))
				.andExpect(jsonPath("$.deterministicSummary.shipmentScheduleFacts[0].factReference").value("schedule:MOTHER:PLANNED_ARRIVAL"))
				.andExpect(jsonPath("$.deterministicSummary.shipmentScheduleFacts[1].eventType").value("PLANNED_DEPARTURE"))
				.andExpect(jsonPath("$.deterministicSummary.evidenceFacts[0].evidenceId").isNotEmpty());

		if (client instanceof CapturingSyntheticClient capturing) {
			MaritimeAiEvidencePackage received = capturing.received();
			assertThat(received).isNotNull();
			assertThat(received.shipmentAssociationConfidence()).isEqualTo("VERIFIED");
			assertThat(received.shipmentContext().motherVessel().plannedMovementType()).isEqualTo("PLANNED_ARRIVAL");
			assertThat(received.shipmentContext().feederVessel().plannedMovementType()).isEqualTo("PLANNED_DEPARTURE");
			assertThat(received.evidence()).extracting(item -> item.evidenceId()).containsExactlyInAnyOrder(
					SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID,
					SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID,
					SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID);
			assertThat(received.evidence()).extracting(item -> item.evidenceId())
					.doesNotContain(SyntheticMultiSourceMaritimeFixture.INCOMPATIBLE_NOTICE_EVIDENCE_ID);
			assertThat(received.evidence()).allSatisfy(item -> {
				assertThat(item.sourceMode()).isEqualTo("SYNTHETIC_DEMONSTRATION");
				assertThat(item.sourceEventAt()).isNotBlank();
				assertThat(item.retrievedAtUtc()).isNotBlank();
				assertThat(item.citation()).isNotBlank();
				assertThat(item.limitation()).isNotBlank();
			});
			assertThat(received.evidence()).filteredOn(item -> item.evidenceId().equals(SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID))
					.singleElement().satisfies(item -> assertThat(item.limitation()).contains("regional weather context"));
		}

		mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].state").value("GENERATED"))
				.andExpect(jsonPath("$[0].interpretation.claims[0].evidenceIds").isArray())
				.andExpect(jsonPath("$[0].deterministicSummary.shipmentScheduleFacts[1].eventType").value("PLANNED_DEPARTURE"));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM maritime_ai_assessments WHERE shipment_id = ?", Long.class, shipmentId)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT planned_mother_arrival_at::text FROM shipments WHERE id = ?", String.class, shipmentId)).isEqualTo(beforeArrival);
		assertThat(jdbc.queryForObject("SELECT planned_feeder_departure_at::text FROM shipments WHERE id = ?", String.class, shipmentId)).isEqualTo(beforeDeparture);
	}

	@Test
	void realClientModeCannotStartWithoutTheExternalRequestApprovalGate() {
		assertThatThrownBy(() -> new MultiSourceConfiguration().multiSourceClient(true, "openai-responses", "gpt-4.1-mini",
				"not-a-key", "https://example.invalid/v1/responses", false, 20, 800, new tools.jackson.databind.ObjectMapper(), false, ""))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true");
	}

	@Test
	void rejectedInterpretationIsNotReturnedAsValidatedAiReasoningButKeepsDeterministicEvidenceSeparate() throws Exception {
		assertThat(client).isInstanceOf(CapturingSyntheticClient.class);
		CapturingSyntheticClient stub = (CapturingSyntheticClient) client;
		stub.setNextResult(new MaritimeAiClient.MaritimeAiClientResult("synthetic-capturing-stub", "no-network",
				ConfiguredMaritimeAiClient.PROMPT_VERSION,
				new MaritimeAiInterpretation("The fictional rainfall caused the documented schedule change.",
						List.of(new MaritimeAiClaim("The fictional rainfall caused a delay.", List.of(SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID), "CONTEXTUAL",
								null, null, "REGIONAL_CONTEXT", List.of("evidence:" + SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID))), List.of(), List.of(), true, null), null));
		try {
			mvc.perform(post("/api/shipments/" + shipmentId + "/maritime-ai-assessments").header("Authorization", "Bearer " + token))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.state").value("FAILED"))
					.andExpect(jsonPath("$.validationStatus").value("UNSUPPORTED_CAUSAL_CLAIM"))
					.andExpect(jsonPath("$.interpretation").doesNotExist())
					.andExpect(jsonPath("$.deterministicSummary.label").value("Backend-owned evidence and shipment-schedule facts; not AI reasoning."));
		} finally {
			stub.clearNextResult();
		}
	}

	@Test
	void readsAnOlderInterpretationOnlyPersistenceShape() throws Exception {
		String legacyJson = "{\"summary\":\"Legacy bounded interpretation.\",\"claims\":[],\"uncertainties\":[],\"followUpConsiderations\":[],\"evidenceSufficient\":false,\"formalRecommendationCode\":null}";
		jdbc.update("""
				INSERT INTO maritime_ai_assessments (shipment_id, assessment_id, owning_company_id, bundle_version, bundle_hash,
				assessment_cutoff_utc, provider, model, prompt_version, generation_state, validation_status, output_json, failure_metadata, created_at)
				VALUES (?, ?, (SELECT company_id FROM shipments WHERE id = ?), 'legacy-v1', ?, now(), 'legacy', 'legacy', 'legacy', 'ABSTAINED', 'INSUFFICIENT_EVIDENCE', ?, 'legacy', now())
				""", shipmentId, UUID.randomUUID().toString(), shipmentId, "0".repeat(64), legacyJson);
		mvc.perform(get("/api/shipments/" + shipmentId + "/maritime-ai-assessments").header("Authorization", "Bearer " + token))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].interpretation.summary").value("Legacy bounded interpretation."));
	}

	@Test
	void explicitCapturePropertyWritesOnlyTheParsedSyntheticStubInterpretation() throws Exception {
		Path output = MultiSourceConfiguration.offlineCapturePath();
		Files.deleteIfExists(output);
		try {
			assertThat(MaritimeAiCanonicalFactReferences.allowed(syntheticEvidencePackage()))
				.contains(MaritimeAiCanonicalFactReferences.PORT_OVERLAP_REFERENCE);
			MaritimeAiClient capturing = new MultiSourceConfiguration().multiSourceClient(false, "", "", "", "", false,
					20, 800, new tools.jackson.databind.ObjectMapper(), true, output.toString());
			MaritimeAiClient.MaritimeAiClientResult result = capturing.interpret(syntheticEvidencePackage());

			assertThat(result.interpretation()).isNotNull();
			assertThat(Files.exists(output)).isTrue();
			var capture = new tools.jackson.databind.ObjectMapper().readTree(Files.readString(output));
			assertThat(capture.path("model").asText()).isEqualTo("no-network");
			assertThat(capture.path("validationStatus").asText()).isEqualTo("VALID");
			assertThat(capture.path("citationIds")).extracting(node -> node.asText()).containsExactlyInAnyOrder(
					SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID,
					SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID,
					SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID);
			assertThat(capture.toString()).doesNotContain("Authorization", "not-a-key", "Bearer ");
			assertThat(Files.getPosixFilePermissions(output)).isEqualTo(PosixFilePermissions.fromString("rw-------"));
		} finally {
			Files.deleteIfExists(output);
		}
	}

	@Test
	void captureRetainsParsedSyntheticOutputWhenTheValidatorRejectsIt() throws Exception {
		Path output = MultiSourceConfiguration.offlineCapturePath();
		Files.deleteIfExists(output);
		try {
			MaritimeAiClient rejectingStub = evidence -> new MaritimeAiClient.MaritimeAiClientResult("synthetic-capturing-stub", "no-network",
					"cdg249.synthetic-multisource.v1", new MaritimeAiInterpretation("Synthetic evidence is available.",
							List.of(new MaritimeAiClaim("The synthetic rainfall caused a delay.",
									List.of(SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID), "CONTEXTUAL")), List.of(), List.of(), true, null), null);
			new SyntheticInterpretationCaptureClient(rejectingStub, new tools.jackson.databind.ObjectMapper(), output).interpret(syntheticEvidencePackage());

			var capture = new tools.jackson.databind.ObjectMapper().readTree(Files.readString(output));
			assertThat(capture.path("validationStatus").asText()).isEqualTo("UNSUPPORTED_CAUSAL_CLAIM");
			assertThat(capture.path("interpretation").path("claims").get(0).path("statement").asText())
					.isEqualTo("The synthetic rainfall caused a delay.");
		} finally {
			Files.deleteIfExists(output);
		}
	}

	@Test
	void captureInvokesItsDelegateExactlyOnce() throws Exception {
		Path output = MultiSourceConfiguration.offlineCapturePath();
		Files.deleteIfExists(output);
		try {
			AtomicInteger invocations = new AtomicInteger();
			CapturingSyntheticClient stub = new CapturingSyntheticClient();
			MaritimeAiClient countingStub = evidence -> {
				invocations.incrementAndGet();
				return stub.interpret(evidence);
			};
			new SyntheticInterpretationCaptureClient(countingStub, new tools.jackson.databind.ObjectMapper(), output).interpret(syntheticEvidencePackage());

			assertThat(invocations.get()).isEqualTo(1);
		} finally {
			Files.deleteIfExists(output);
		}
	}

	@Test
	void captureRefusesTheProtectedHistoricalCaptureDestination() {
		Path protectedPath = Path.of(System.getProperty("user.dir"), "target", HISTORICAL_CAPTURE_FILE);
		assertThatThrownBy(() -> new SyntheticInterpretationCaptureClient(new CapturingSyntheticClient(),
				new tools.jackson.databind.ObjectMapper(), protectedPath))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("protected historical capture");
	}

	private static MaritimeAiEvidencePackage syntheticEvidencePackage() {
		MaritimeAiShipmentContext context = new MaritimeAiShipmentContext("Synthetic Multi-source Port",
				new MaritimeAiShipmentContext.Vessel("MOTHER", "Synthetic Multi-source Vessel", "9000002", "PLANNED_ARRIVAL", java.time.OffsetDateTime.parse("2026-10-01T05:00:00+08:00")),
				new MaritimeAiShipmentContext.Vessel("FEEDER", "Synthetic Multi-source Feeder", "9000003", "PLANNED_DEPARTURE", java.time.OffsetDateTime.parse("2026-10-01T14:00:00+08:00")));
		return new MaritimeAiEvidencePackage("cdg246.maritime-context.v1", "2026-10-01T00:00:00Z", "VERIFIED", context, List.of(
				syntheticEvidence(SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID, "MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION"),
				syntheticEvidence(SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID, null, null, "PORT_CONTEXT"),
				syntheticEvidence(SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID, null, null, "REGIONAL_CONTEXT")));
	}

	private static com.drift.backend.maritimecontext.MaritimeEvidence syntheticEvidence(String id, String role, String observationType, String associationScope) {
		String sourceType = "VESSEL_OBSERVATION".equals(associationScope) ? "MPA_ETA_REVISION"
				: "PORT_CONTEXT".equals(associationScope) ? "MPA_OFFICIAL_NOTICE" : "NEA_WEATHER";
		String previousValue = "MPA_ETA_REVISION".equals(sourceType) ? "2026-10-01T05:00:00+08:00" : null;
		String currentValue = "MPA_ETA_REVISION".equals(sourceType) ? "2026-10-01T06:00:00+08:00"
				: "MPA_OFFICIAL_NOTICE".equals(sourceType) ? "Effective 2026-10-01 06:00-12:00 SGT"
				: "Rainfall observation: 12.0 mm";
		return new com.drift.backend.maritimecontext.MaritimeEvidence(sourceType, "SYNTHETIC_DEMONSTRATION", id,
				"Synthetic evidence", "synthetic", "https://example.invalid/synthetic", "Synthetic-only citation.",
				"2026-10-01T00:00:00+08:00", "2026-10-01T00:00:00+08:00", "2026-09-30T22:00:00Z", "FRESH",
				previousValue, currentValue, "MPA_ETA_REVISION".equals(sourceType) ? "+60 minutes" : null, "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true, "VERIFIED",
				"Synthetic-only fixture evidence.", "Synthetic Multi-source Vessel", role, observationType, associationScope);
	}

	private static String shipmentJson() {
		return """
				{"shipmentReference":"SYNTHETIC-MULTI-%s","origin":"Synthetic origin","destination":"Synthetic destination",
				"transshipmentPort":"Synthetic Multi-source Port","motherVessel":"Synthetic Multi-source Vessel","motherVesselImo":"9000002",
				"plannedMotherArrivalAt":"2026-10-01T05:00:00+08:00","feederVessel":"Synthetic Multi-source Feeder",
				"plannedFeederDepartureAt":"2026-10-01T14:00:00+08:00"}
				""".formatted(UUID.randomUUID());
	}

	@TestConfiguration
	static class MultiSourceConfiguration {
		@Bean @Primary MaritimeContextService multiSourceContext() { return SyntheticMultiSourceMaritimeFixture.context(); }
		@Bean @Primary MaritimeAiClient multiSourceClient(
				@Value("${cdg247.synthetic-multisource.use-real-client:false}") boolean useRealClient,
				@Value("${app.maritime-ai.provider:}") String provider,
				@Value("${app.maritime-ai.model:}") String model,
				@Value("${app.maritime-ai.api-key:}") String apiKey,
				@Value("${app.maritime-ai.endpoint:}") String endpoint,
				@Value("${app.maritime-ai.external-requests-approved:false}") boolean externalRequestsApproved,
				@Value("${app.maritime-ai.timeout-seconds:20}") int timeoutSeconds,
				@Value("${app.maritime-ai.max-output-tokens:800}") int maxOutputTokens,
				tools.jackson.databind.ObjectMapper json,
				@Value("${cdg247.synthetic-multisource.capture-interpretation:false}") boolean captureInterpretation,
				@Value("${cdg247.synthetic-multisource.capture-path:}") String capturePath) {
			MaritimeAiClient delegate;
			if (useRealClient) {
				if (!externalRequestsApproved) {
					throw new IllegalStateException("Real multi-source smoke test requires MARITIME_AI_EXTERNAL_REQUESTS_APPROVED=true");
				}
				if (!"APPROVED".equals(System.getenv("CDG247_RUN_REAL_SMOKE"))) {
					throw new IllegalStateException("Real multi-source smoke test requires CDG247_RUN_REAL_SMOKE=APPROVED");
				}
				delegate = new ConfiguredMaritimeAiClient(provider, model, apiKey, endpoint, externalRequestsApproved,
						timeoutSeconds, maxOutputTokens, json, (request, ignoredBody) -> {
						var http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
						var response = http.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
						return new ConfiguredMaritimeAiClient.TransportResponse(response.statusCode(), response.body());
					});
			} else {
				delegate = new CapturingSyntheticClient();
			}
			return captureInterpretation ? new SyntheticInterpretationCaptureClient(delegate, json, capturePath(capturePath)) : delegate;
		}

		static Path capturePath(String configured) {
			if (configured != null && !configured.isBlank()) return Path.of(configured);
			return Path.of(System.getProperty("user.dir"), "target", "cdg247-synthetic-multisource-interpretation-postfix-real-response.json");
		}

		static Path offlineCapturePath() {
			return Path.of(System.getProperty("user.dir"), "target", "cdg247-synthetic-multisource-interpretation-offline-test.json");
		}
	}

	/** Test-scope capture: never a Spring runtime component and disabled unless its JVM property is true. */
	static final class SyntheticInterpretationCaptureClient implements MaritimeAiClient {
		private static final Set<String> EXPECTED_IDS = Set.of(SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID,
				SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID, SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID);
		private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");
		private final MaritimeAiClient delegate;
		private final tools.jackson.databind.ObjectMapper json;
		private final Path output;

		SyntheticInterpretationCaptureClient(MaritimeAiClient delegate, tools.jackson.databind.ObjectMapper json, Path output) {
			if (HISTORICAL_CAPTURE_FILE.equals(output.getFileName().toString())) {
				throw new IllegalArgumentException("Refusing to overwrite the protected historical capture destination.");
			}
			this.delegate = delegate;
			this.json = json;
			this.output = output;
		}

		@Override public MaritimeAiClientResult interpret(MaritimeAiEvidencePackage evidence) {
			verifySyntheticFixtureOnly(evidence);
			MaritimeAiClientResult result = delegate.interpret(evidence);
			writeCapture(evidence, result);
			return result;
		}

		private static void verifySyntheticFixtureOnly(MaritimeAiEvidencePackage evidence) {
			Set<String> ids = evidence.evidence().stream().map(item -> item.evidenceId()).collect(Collectors.toSet());
			if (!EXPECTED_IDS.equals(ids) || evidence.evidence().stream().anyMatch(item -> !"SYNTHETIC_DEMONSTRATION".equals(item.sourceMode()))) {
				throw new IllegalArgumentException("Synthetic interpretation capture accepts only the fixed synthetic multi-source fixture.");
			}
		}

		private void writeCapture(MaritimeAiEvidencePackage evidence, MaritimeAiClientResult result) {
			try {
				String validationStatus = result.interpretation() == null ? "MODEL_UNAVAILABLE"
						: java.util.Optional.ofNullable(MaritimeAiResponseValidator.validate(result.interpretation(), evidence)).orElse("VALID");
				List<String> citationIds = result.interpretation() == null || result.interpretation().claims() == null ? List.of()
						: result.interpretation().claims().stream().filter(java.util.Objects::nonNull)
								.filter(claim -> claim.evidenceIds() != null).flatMap(claim -> claim.evidenceIds().stream()).distinct().toList();
				var capture = new SyntheticInterpretationCapture("cdg247.synthetic-multisource.capture.v2.role-grounding", result.provider(), result.model(),
						result.promptVersion(), validationStatus, citationIds, result.interpretation());
				Files.createDirectories(output.getParent());
				Path temporary = Files.createTempFile(output.getParent(), ".cdg247-synthetic-", ".json");
				try {
					setOwnerOnly(temporary);
					Files.writeString(temporary, json.writeValueAsString(capture), StandardCharsets.UTF_8);
					try {
						Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
					} catch (AtomicMoveNotSupportedException ignored) {
						Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
					}
					setOwnerOnly(output);
				} finally {
					Files.deleteIfExists(temporary);
				}
			} catch (IOException ex) {
				throw new IllegalStateException("Unable to write synthetic interpretation capture", ex);
			}
		}

		private static void setOwnerOnly(Path path) throws IOException {
			try {
				Files.setPosixFilePermissions(path, OWNER_ONLY);
			} catch (UnsupportedOperationException ex) {
				throw new IOException("Synthetic interpretation capture requires POSIX owner-only permissions", ex);
			}
		}
	}

	private record SyntheticInterpretationCapture(String captureFormatVersion, String provider, String model, String promptVersion,
			String validationStatus, List<String> citationIds, MaritimeAiInterpretation interpretation) { }

	static final class CapturingSyntheticClient implements MaritimeAiClient {
		private MaritimeAiEvidencePackage received;
		private MaritimeAiClientResult nextResult;
		@Override public MaritimeAiClientResult interpret(MaritimeAiEvidencePackage evidence) {
			received = evidence;
			if (nextResult != null) return nextResult;
			return new MaritimeAiClientResult("synthetic-capturing-stub", "no-network", ConfiguredMaritimeAiClient.PROMPT_VERSION,
					new MaritimeAiInterpretation("AI planning interpretation using separately displayed backend facts.",
							List.of(
									new MaritimeAiClaim("Review the documented schedule change against the shipment plan.",
											List.of(SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID), "CONTEXTUAL",
											"MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION", List.of("evidence:" + SyntheticMultiSourceMaritimeFixture.MPA_EVIDENCE_ID, "schedule:MOTHER:PLANNED_ARRIVAL", "schedule:FEEDER:PLANNED_DEPARTURE"), "SCHEDULE_REVIEW"),
									new MaritimeAiClaim("The fictional port-area maintenance notice is contextual evidence only.",
											List.of(SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID), "CONTEXTUAL",
											null, null, "PORT_CONTEXT", List.of("evidence:" + SyntheticMultiSourceMaritimeFixture.NOTICE_EVIDENCE_ID,
														MaritimeAiCanonicalFactReferences.PORT_OVERLAP_REFERENCE), "PORT_TIME_OVERLAP_REVIEW"),
									new MaritimeAiClaim("The fictional rainfall observation is regional context only.",
											List.of(SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID), "CONTEXTUAL",
											null, null, "REGIONAL_CONTEXT", List.of("evidence:" + SyntheticMultiSourceMaritimeFixture.NEA_EVIDENCE_ID), "REGIONAL_WEATHER_CONTEXT")),
							List.of("The fictional weather observation does not establish a vessel-specific cause."), List.of(), true, null), null);
		}
		MaritimeAiEvidencePackage received() { return received; }
		void setNextResult(MaritimeAiClientResult result) { nextResult = result; }
		void clearNextResult() { nextResult = null; }
	}
}
