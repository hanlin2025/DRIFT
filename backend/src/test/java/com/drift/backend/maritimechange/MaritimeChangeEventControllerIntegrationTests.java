package com.drift.backend.maritimechange;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.maritimechange.MaritimeSnapshotChangeDetector;
import com.drift.backend.company.Company;
import com.drift.backend.company.CompanyRepository;
import com.drift.backend.shipment.Shipment;
import com.drift.backend.shipment.ShipmentRepository;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "DRIFT_CDG358_JDBC_URL",
		matches = "jdbc:postgresql://127\\.0\\.0\\.1:5432/drift_cdg358_[0-9]{14}_[a-f0-9]{12}")
@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG358_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG358_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG358_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG358_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG358_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG358_DB_PASSWORD}",
		"app.ais.enabled=false",
		"app.maritime-change.collection.enabled=true",
		"app.maritime-change.collection.initial-delay-ms=86400000",
		"drift.startup-guard.expected-url=${DRIFT_CDG358_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG358_OWNERSHIP_TOKEN}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = MaritimeChangeEventDatabaseGuard.class)
@Transactional
class MaritimeChangeEventControllerIntegrationTests {
	private static final String MOTHER_IMO = "9351048";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired CompanyRepository companies;
	@Autowired ShipmentRepository shipments;
	@Autowired EntityManager entityManager;
	@Autowired MaritimeSnapshotChangeDetector detector;
	@Autowired JpaMaritimeSnapshotObservationStore observationStore;

	private long shipmentId;
	private String ownerToken;
	private String otherToken;
	private java.time.Instant shipmentUpdatedAt;

	@BeforeEach
	void setUp() throws Exception {
		Company owner = companies.findByCode("HARBOURLINE_DEMO").orElseThrow();
		Company outsider = companies.findByCode("STRAITS_FRESH_DEMO").orElseThrow();
		String ownerEmail = "cdg358-owner-" + UUID.randomUUID() + "@example.com";
		String otherEmail = "cdg358-other-" + UUID.randomUUID() + "@example.com";
		createUser(ownerEmail, owner.getId());
		createUser(otherEmail, outsider.getId());
		ownerToken = login(ownerEmail);
		otherToken = login(otherEmail);
		var actor = entityManager.createQuery("SELECT account FROM UserAccount account WHERE account.email = :email",
				com.drift.backend.account.UserAccount.class).setParameter("email", ownerEmail).getSingleResult();
		Shipment shipment = new Shipment(owner, actor, "CDG358-" + UUID.randomUUID(), "Jakarta", "Rotterdam", "Singapore",
				"KOTA LARIS", OffsetDateTime.parse("2026-10-28T08:00:00Z"), "FEEDER",
				OffsetDateTime.parse("2026-10-28T20:00:00Z"));
		shipments.saveAndFlush(shipment);
		shipmentId = shipment.getId();
		jdbc.update("UPDATE shipments SET mother_vessel_imo = ? WHERE id = ?", MOTHER_IMO, shipmentId);
		entityManager.clear();
		detector.accept(List.of(new MaritimeSnapshot("MPA_ETA_REVISION", "mpa:" + MOTHER_IMO + ":SEA:PEBGA",
				"MPA OCEANS-X Vessels Due to Arrive; recorded replay", "2026-10-29T02:00:00+08:00",
				"2026-10-26T15:46:03Z", "2026-10-26T15:46:03Z", "RECORDED_REPLAY", "HISTORICAL", MOTHER_IMO,
				"Singapore", null, null, null, "2026-10-29T02:00:00+08:00", "UNKNOWN", "UNKNOWN", "UNKNOWN")));
		shipmentUpdatedAt = jdbc.queryForObject("SELECT updated_at FROM shipments WHERE id = ?", java.sql.Timestamp.class, shipmentId).toInstant();
	}

	@Test
	void authenticatedVisibleShipmentCanReadEventsAndOutsiderCannot() throws Exception {
		mvc.perform(get("/api/shipments/{id}/maritime-change-events", shipmentId)
					.header("Authorization", "Bearer " + ownerToken)
					.param("since", "2026-10-01T00:00:00Z").param("limit", "10"))
				.andExpect(status().isOk())
				.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.availability").value("PARTIAL"))
				.andExpect(jsonPath("$.events[0].sourceType").value("MPA_ETA_REVISION"))
				.andExpect(jsonPath("$.events[0].eventType").value("NEW"))
				.andExpect(jsonPath("$.events[0].sourceMode").value("RECORDED_REPLAY"))
				.andExpect(jsonPath("$.events[0].associationConfidence").value("UNVERIFIED_IMO_ONLY"))
				.andExpect(jsonPath("$.events[0].previousValue").doesNotExist())
				.andExpect(jsonPath("$.events[0].currentValue").value("2026-10-29T02:00:00+08:00"))
				.andExpect(jsonPath("$.events[0].requiresHumanReview").value(true));

		mvc.perform(get("/api/shipments/{id}/maritime-change-events", shipmentId)
					.header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/shipments/{id}/maritime-change-events", shipmentId))
				.andExpect(status().isUnauthorized());
		org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("SELECT updated_at FROM shipments WHERE id = ?",
				java.sql.Timestamp.class, shipmentId).toInstant()).isEqualTo(shipmentUpdatedAt);
	}

	@Test
	void authenticatedEventPagingStartsNewestAndCursorLoadsOlderChanges() throws Exception {
		String revisedArrival = "2026-11-15T02:00:00+08:00";
		detector.accept(List.of(new MaritimeSnapshot("MPA_ETA_REVISION", "mpa:" + MOTHER_IMO + ":SEA:PEBGA",
				"MPA OCEANS-X Vessels Due to Arrive", revisedArrival, Instant.now().toString(), Instant.now().toString(),
				"LIVE", "APPLICABLE", MOTHER_IMO, "Singapore", revisedArrival, null, null,
				"arrival=" + revisedArrival, "UNKNOWN", "UNKNOWN", "UNKNOWN")));

		MvcResult firstPage = mvc.perform(get("/api/shipments/{id}/maritime-change-events", shipmentId)
					.header("Authorization", "Bearer " + ownerToken).param("since", "2020-01-01T00:00:00Z")
					.param("limit", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events[0].eventType").value("REVISED"))
				.andExpect(jsonPath("$.events[0].currentValue").value("arrival=" + revisedArrival))
				.andExpect(jsonPath("$.nextCursor").isNotEmpty())
				.andReturn();
		String cursor = JsonPath.read(firstPage.getResponse().getContentAsString(), "$.nextCursor");
		mvc.perform(get("/api/shipments/{id}/maritime-change-events", shipmentId)
					.header("Authorization", "Bearer " + ownerToken).param("since", "2020-01-01T00:00:00Z")
					.param("limit", "1").param("cursor", cursor))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.events[0].eventType").value("NEW"))
				.andExpect(jsonPath("$.events[0].currentValue").value("2026-10-29T02:00:00+08:00"))
				.andExpect(jsonPath("$.nextCursor").doesNotExist());
	}

	@Test
	void persistedEventPagingReturnsNewestThenOlderUsingStableRowId() {
		Instant sameDetectedAt = Instant.parse("2099-01-01T00:00:00Z");
		observationStore.add(observation("cdg358-page-a", "a".repeat(64), sameDetectedAt), false);
		observationStore.add(observation("cdg358-page-b", "b".repeat(64), sameDetectedAt), false);

		List<MaritimeSnapshotObservation> firstPage = observationStore.before(sameDetectedAt, null, 0, 1);
		List<MaritimeSnapshotObservation> secondPage = observationStore.before(sameDetectedAt,
				firstPage.getFirst().getRecordedAt(), firstPage.getFirst().getId(), 1);

		org.assertj.core.api.Assertions.assertThat(firstPage).singleElement()
				.extracting(MaritimeSnapshotObservation::getSourceIdentity).isEqualTo("cdg358-page-b");
		org.assertj.core.api.Assertions.assertThat(secondPage).singleElement()
				.extracting(MaritimeSnapshotObservation::getSourceIdentity).isEqualTo("cdg358-page-a");
		org.assertj.core.api.Assertions.assertThat(firstPage.getFirst().getId())
				.isGreaterThan(secondPage.getFirst().getId());
	}

	private static MaritimeSnapshotObservation observation(String identity, String fingerprint, Instant recordedAt) {
		return MaritimeSnapshotObservation.record("MPA_OFFICIAL_NOTICE", identity, fingerprint, "NEW", "FORWARD", false,
				"CDG-358 paging test", null, "2099-01-01T00:00:00Z", recordedAt, "RECORDED_REPLAY", "HISTORICAL",
				null, "Singapore", null, null, null, identity, "UNKNOWN", null, "UNKNOWN", recordedAt);
	}

	private void createUser(String email, long companyId) {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'CDG-358 API Viewer', ?, ?, id, ? FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", email, passwords.encode("Example123"), companyId);
	}

	private String login(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk()).andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}
}
