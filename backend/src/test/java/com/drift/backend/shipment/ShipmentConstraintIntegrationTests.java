package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.drift.backend.account.Role;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentConstraintEditForbiddenException;
import com.drift.backend.shipment.exception.ShipmentConstraintPortConflictException;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotEditableException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest(properties = {
		"spring.datasource.url=${DRIFT_CDG203_JDBC_URL}",
		"spring.flyway.url=${DRIFT_CDG203_JDBC_URL}",
		"spring.datasource.username=${DRIFT_CDG203_DB_USERNAME}",
		"spring.datasource.password=${DRIFT_CDG203_DB_PASSWORD}",
		"spring.flyway.user=${DRIFT_CDG203_DB_USERNAME}",
		"spring.flyway.password=${DRIFT_CDG203_DB_PASSWORD}",
		"spring.flyway.out-of-order=false",
		"app.ais.enabled=false",
		"app.risk-alerts.evaluation.enabled=false",
		"app.risk-alerts.mail.enabled=false",
		"app.risk-alerts.mail.dispatch-enabled=false",
		"app.risk-alerts.activation.command=false",
		"drift.startup-guard.expected-url=${DRIFT_CDG203_JDBC_URL}",
		"drift.startup-guard.ownership-token=${DRIFT_CDG203_OWNERSHIP_TOKEN}"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ContextConfiguration(initializers = ShipmentConstraintDatabaseGuard.class)
class ShipmentConstraintIntegrationTests {

	private static final String DEADLINE = "2026-10-28T16:00:00Z";
	private static final String SAME_INSTANT = "2026-10-29T00:00:00+08:00";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired PlatformTransactionManager transactions;
	@Autowired ShipmentRepository shipments;

	@Test
	void savesConfirmsAndRereadsTheDeadlineForTheCurrentPort() throws Exception {
		Fixture fixture = linkedShipment("save");
		Row before = constraint(fixture.shipmentId(), fixture.importerCompanyId());
		assertThat(before).isNull();
		long version = version(fixture.shipmentId());

		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(SAME_INSTANT, " Singapore "))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.deadline").value(DEADLINE))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.transshipmentPort").value("Singapore"))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.applicable").value(true));

		mvc.perform(get("/api/shipments/" + fixture.shipmentId())
				.header("Authorization", "Bearer " + fixture.importerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.deadline").value(DEADLINE))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.applicable").value(true))
				.andExpect(jsonPath("$.arrivalComparison").exists());
		assertThat(constraint(fixture.shipmentId(), fixture.importerCompanyId()).deadline()).isEqualTo(DEADLINE);
		assertThat(version(fixture.shipmentId())).isEqualTo(version);

		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody("2026-10-29T16:00:00Z", "Singapore"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.deadline").value("2026-10-29T16:00:00Z"));
		assertThat(constraint(fixture.shipmentId(), fixture.importerCompanyId()).deadline())
				.isEqualTo("2026-10-29T16:00:00Z");
	}

	@Test
	void explicitClearRemovesTheRowAndRelinkingDoesNotRestoreIt() throws Exception {
		Fixture fixture = linkedShipment("clear");
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(DEADLINE, "Singapore"))
				.andExpect(status().isOk());

		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(null, "Singapore"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transshipmentArrivalDeadline").value(org.hamcrest.Matchers.nullValue()));
		assertThat(constraint(fixture.shipmentId(), fixture.importerCompanyId())).isNull();

		link(fixture.forwarderToken(), fixture.shipmentId(), null);
		link(fixture.forwarderToken(), fixture.shipmentId(), fixture.importerCompanyId());
		mvc.perform(get("/api/shipments/" + fixture.shipmentId())
				.header("Authorization", "Bearer " + fixture.importerToken()))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline").value(org.hamcrest.Matchers.nullValue()));
		assertThat(constraint(fixture.shipmentId(), fixture.importerCompanyId())).isNull();
	}

	@Test
	void rejectedBodiesLeaveTheSavedDeadlineUnchanged() throws Exception {
		Fixture fixture = linkedShipment("reject");
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(DEADLINE, "Singapore"))
				.andExpect(status().isOk());

		changeDeadline(fixture.importerToken(), fixture.shipmentId(), "{\"expectedTransshipmentPort\":\"Singapore\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"))
				.andExpect(jsonPath("$.errors.transshipmentArrivalDeadline")
						.value(ShipmentConstraintRequest.DEADLINE_REQUIRED));
		changeDeadline(fixture.importerToken(), fixture.shipmentId(),
				"{\"transshipmentArrivalDeadline\":\"2026-10-28\",\"expectedTransshipmentPort\":\"Singapore\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.transshipmentArrivalDeadline")
						.value(ShipmentConstraintRequest.DEADLINE_INVALID));
		changeDeadline(fixture.importerToken(), fixture.shipmentId(),
				"{\"transshipmentArrivalDeadline\":\"2026-10-28T16:00:00\",\"expectedTransshipmentPort\":\"Singapore\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.transshipmentArrivalDeadline")
						.value(ShipmentConstraintRequest.DEADLINE_INVALID));
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), "{\"transshipmentArrivalDeadline\":null}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.expectedTransshipmentPort")
						.value(ShipmentConstraintRequest.PORT_REQUIRED));
		changeDeadline(fixture.importerToken(), fixture.shipmentId(),
				"{\"transshipmentArrivalDeadline\":null,\"expectedTransshipmentPort\":\" \"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.expectedTransshipmentPort")
						.value(ShipmentConstraintRequest.PORT_REQUIRED));
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), "null")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"))
				.andExpect(jsonPath("$.errors").isEmpty());

		assertThat(constraint(fixture.shipmentId(), fixture.importerCompanyId()).deadline()).isEqualTo(DEADLINE);
		assertThat(constraint(fixture.shipmentId(), fixture.importerCompanyId()).port()).isEqualTo("Singapore");
	}

	@Test
	void anOutdatedExpectedPortRejectsSaveAndClearWithoutChangingTheRow() throws Exception {
		Fixture fixture = linkedShipment("stale-port");
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(DEADLINE, "Singapore"))
				.andExpect(status().isOk());
		replacePort(fixture.forwarderToken(), fixture.shipmentId(), "Tanjung Pelepas");

		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody("2026-11-01T00:00:00Z", "Singapore"))
				.andExpect(status().isConflict())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ShipmentConstraintPortConflictException.MESSAGE));
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(null, "Singapore"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(ShipmentConstraintPortConflictException.MESSAGE));

		Row saved = constraint(fixture.shipmentId(), fixture.importerCompanyId());
		assertThat(saved.deadline()).isEqualTo(DEADLINE);
		assertThat(saved.port()).isEqualTo("Singapore");
		mvc.perform(get("/api/shipments/" + fixture.shipmentId())
				.header("Authorization", "Bearer " + fixture.forwarderToken()))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.transshipmentPort").value("Singapore"))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.applicable").value(false));
	}

	@Test
	void confirmingTheCurrentPortRebindsTheDeadline() throws Exception {
		Fixture fixture = linkedShipment("confirm");
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(DEADLINE, "Singapore"))
				.andExpect(status().isOk());
		replacePort(fixture.forwarderToken(), fixture.shipmentId(), "Tanjung Pelepas");

		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(DEADLINE, "Tanjung Pelepas"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.transshipmentPort").value("Tanjung Pelepas"))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.applicable").value(true));
		assertThat(constraint(fixture.shipmentId(), fixture.importerCompanyId()).port()).isEqualTo("Tanjung Pelepas");
	}

	@Test
	void everyImporterRoleCanWriteWhileShipmentManagementStaysWithTheManagingCompany() throws Exception {
		Fixture fixture = linkedShipment("roles");
		for (Role role : Role.values()) {
			String token = tokenFor(account(role.name(), fixture.importerCompanyId()));
			String instant = "2026-10-28T16:00:0" + role.ordinal() + "Z";
			changeDeadline(token, fixture.shipmentId(), deadlineBody(instant, "Singapore"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.transshipmentArrivalDeadline.deadline").value(instant));
			putShipment(token, fixture.shipmentId(), "CDG203-ROLES-" + role.name(), version(fixture.shipmentId()))
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE));
		}

		String manager = tokenFor(account("LOGISTICS_MANAGER", fixture.forwarderCompanyId()));
		mvc.perform(get("/api/shipments/" + fixture.shipmentId()).header("Authorization", "Bearer " + manager))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.deadline").exists());
		changeDeadline(manager, fixture.shipmentId(), deadlineBody(DEADLINE, "Singapore"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentConstraintEditForbiddenException.MESSAGE));
		putShipment(manager, fixture.shipmentId(), "CDG203-MANAGER-EDIT", version(fixture.shipmentId()))
				.andExpect(status().isOk());
	}

	@Test
	void preservesTheExistingForbiddenAndNotFoundResponses() throws Exception {
		Fixture fixture = linkedShipment("access");
		String other = tokenFor(account("IMPORTER", companyId("HARBOURLINE_DEMO") == fixture.forwarderCompanyId()
				? insertCompany("CDG203_OTHER_" + UUID.randomUUID().toString().substring(0, 8))
				: insertCompany("CDG203_OTHER_" + UUID.randomUUID().toString().substring(0, 8))));
		mvc.perform(get("/api/shipments/" + fixture.shipmentId()).header("Authorization", "Bearer " + other))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE));
		changeDeadline(other, fixture.shipmentId(), deadlineBody(DEADLINE, "Singapore"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentDetailForbiddenException.MESSAGE));
		changeDeadline(fixture.importerToken(), 999_999_999L, deadlineBody(DEADLINE, "Singapore"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));

		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", fixture.importerCompanyId());
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(DEADLINE, "Singapore"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentAccessForbiddenException.MESSAGE));
		jdbc.update("UPDATE companies SET active = TRUE WHERE id = ?", fixture.importerCompanyId());
	}

	@Test
	void anArchivedShipmentStaysReadableAndRefusesConstraintEdits() throws Exception {
		Fixture fixture = linkedShipment("archived");
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(DEADLINE, "Singapore"))
				.andExpect(status().isOk());
		String admin = tokenFor(account("ADMIN", fixture.forwarderCompanyId()));
		mvc.perform(patch("/api/shipments/" + fixture.shipmentId() + "/status")
				.header("Authorization", "Bearer " + admin)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"ARCHIVED\"}"))
				.andExpect(status().isOk());

		mvc.perform(get("/api/shipments/" + fixture.shipmentId())
				.header("Authorization", "Bearer " + fixture.importerToken()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.deadline").value(DEADLINE));
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(null, "Singapore"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentNotEditableException.MESSAGE));
		assertThat(constraint(fixture.shipmentId(), fixture.importerCompanyId()).deadline()).isEqualTo(DEADLINE);
	}

	@Test
	void replacingAndRelinkingImportersKeepsEachDeadlineOnItsRecordedPort() throws Exception {
		Fixture fixture = linkedShipment("relink");
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(DEADLINE, "Singapore"))
				.andExpect(status().isOk());
		Long otherCompany = insertCompany("CDG203_B_" + UUID.randomUUID().toString().substring(0, 8));
		String otherToken = tokenFor(account("IMPORTER", otherCompany));
		link(fixture.forwarderToken(), fixture.shipmentId(), otherCompany);

		mvc.perform(get("/api/shipments/" + fixture.shipmentId())
				.header("Authorization", "Bearer " + fixture.forwarderToken()))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline").value(org.hamcrest.Matchers.nullValue()));
		mvc.perform(get("/api/shipments/" + fixture.shipmentId()).header("Authorization", "Bearer " + otherToken))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline").value(org.hamcrest.Matchers.nullValue()));
		changeDeadline(otherToken, fixture.shipmentId(), deadlineBody("2026-11-02T00:00:00Z", "Singapore"))
				.andExpect(status().isOk());
		assertThat(constraint(fixture.shipmentId(), fixture.importerCompanyId()).deadline()).isEqualTo(DEADLINE);
		assertThat(constraint(fixture.shipmentId(), otherCompany).deadline()).isEqualTo("2026-11-02T00:00:00Z");

		replacePort(fixture.forwarderToken(), fixture.shipmentId(), "Tanjung Pelepas");
		link(fixture.forwarderToken(), fixture.shipmentId(), fixture.importerCompanyId());
		mvc.perform(get("/api/shipments/" + fixture.shipmentId())
				.header("Authorization", "Bearer " + fixture.importerToken()))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.deadline").value(DEADLINE))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.transshipmentPort").value("Singapore"))
				.andExpect(jsonPath("$.transshipmentArrivalDeadline.applicable").value(false));
		assertThat(constraint(fixture.shipmentId(), otherCompany).port()).isEqualTo("Singapore");
	}

	@Test
	void shipmentReadsAndRouteEditsKeepTheirExistingBehavior() throws Exception {
		Fixture fixture = linkedShipment("unchanged");
		String list = mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + fixture.forwarderToken()))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		assertThat(list).doesNotContain("transshipmentArrivalDeadline");

		long version = version(fixture.shipmentId());
		putShipment(fixture.forwarderToken(), fixture.shipmentId(), "CDG203-UNCHANGED", version)
				.andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT transshipment_port FROM shipments WHERE id = ?", String.class,
				fixture.shipmentId())).isEqualTo("Singapore");
		assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
				String.class)).containsSubsequence("26", "27", "28");
	}

	@Test
	void aConcurrentPortChangeCannotRebindTheDeadline() throws Exception {
		Fixture fixture = linkedShipment("concurrent");
		changeDeadline(fixture.importerToken(), fixture.shipmentId(), deadlineBody(DEADLINE, "Singapore"))
				.andExpect(status().isOk());
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		TransactionTemplate template = new TransactionTemplate(transactions);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> holder = executor.submit(() -> template.executeWithoutResult(status -> {
				shipments.findByIdForUpdate(fixture.shipmentId()).orElseThrow();
				locked.countDown();
				try {
					if (!release.await(8, TimeUnit.SECONDS)) {
						throw new IllegalStateException("The constraint request did not arrive.");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(exception);
				}
				jdbc.update("UPDATE shipments SET transshipment_port = ? WHERE id = ?", "Tanjung Pelepas",
						fixture.shipmentId());
			}));
			assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
			Future<MvcResult> request = executor.submit(() -> changeDeadline(fixture.importerToken(), fixture.shipmentId(),
					deadlineBody("2026-12-01T00:00:00Z", "Singapore")).andReturn());
			Thread.sleep(300);
			release.countDown();
			MvcResult result = request.get(8, TimeUnit.SECONDS);
			holder.get(8, TimeUnit.SECONDS);
			assertThat(result.getResponse().getStatus()).isEqualTo(409);
			assertThat(JsonPath.read(result.getResponse().getContentAsString(), "$.message").toString())
					.isEqualTo(ShipmentConstraintPortConflictException.MESSAGE);
		} finally {
			release.countDown();
			executor.shutdownNow();
		}
		Row saved = constraint(fixture.shipmentId(), fixture.importerCompanyId());
		assertThat(saved.deadline()).isEqualTo(DEADLINE);
		assertThat(saved.port()).isEqualTo("Singapore");
	}

	private Fixture linkedShipment(String label) throws Exception {
		Long forwarderCompanyId = companyId("HARBOURLINE_DEMO");
		Long importerCompanyId = companyId("STRAITS_FRESH_DEMO");
		String forwarderToken = tokenFor(account("FREIGHT_FORWARDER", forwarderCompanyId));
		String importerToken = tokenFor(account("IMPORTER", importerCompanyId));
		Long shipmentId = createShipment(forwarderToken, "CDG203-" + label + "-" + UUID.randomUUID());
		link(forwarderToken, shipmentId, importerCompanyId);
		return new Fixture(shipmentId, forwarderCompanyId, importerCompanyId, forwarderToken, importerToken);
	}

	private org.springframework.test.web.servlet.ResultActions changeDeadline(String token, Long shipmentId, String body)
			throws Exception {
		return mvc.perform(patch("/api/shipments/" + shipmentId + "/transshipment-arrival-deadline")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private org.springframework.test.web.servlet.ResultActions putShipment(String token, Long shipmentId, String reference,
			long version) throws Exception {
		return mvc.perform(put("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00",
						  "version": %d
						}
						""".formatted(reference, version)));
	}

	private void replacePort(String token, Long shipmentId, String port) throws Exception {
		long version = version(shipmentId);
		String reference = jdbc.queryForObject("SELECT shipment_reference FROM shipments WHERE id = ?", String.class,
				shipmentId);
		mvc.perform(put("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "%s",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00",
						  "version": %d
						}
						""".formatted(reference, port, version)))
				.andExpect(status().isOk());
	}

	private void link(String token, Long shipmentId, Long importerCompanyId) throws Exception {
		String body = importerCompanyId == null ? "{\"importerCompanyId\":null}"
				: "{\"importerCompanyId\":" + importerCompanyId + "}";
		mvc.perform(patch("/api/shipments/" + shipmentId + "/link-importer")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isOk());
	}

	private Long createShipment(String token, String reference) throws Exception {
		MvcResult result = mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + token)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "shipmentReference": "%s",
						  "origin": "Shanghai, CN",
						  "destination": "Jakarta, ID",
						  "transshipmentPort": "Singapore",
						  "motherVessel": "MV Pacific Horizon",
						  "plannedMotherArrivalAt": "2026-10-15T08:00:00+08:00",
						  "feederVessel": "MV Strait Runner",
						  "plannedFeederDepartureAt": "2026-10-16T12:00:00+08:00"
						}
						""".formatted(reference)))
				.andExpect(status().isCreated())
				.andReturn();
		return ((Number) JsonPath.read(result.getResponse().getContentAsString(), "$.id")).longValue();
	}

	private String account(String role, Long companyId) {
		String email = "cdg203-" + role.toLowerCase() + "-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Constraint User', ?, ?, id, ?
				FROM roles WHERE code = ?
				""", email, passwords.encode("Example123"), companyId, role);
		return email;
	}

	private String tokenFor(String email) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(email)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private Long insertCompany(String code) {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, ?, TRUE)", code, code);
		return companyId(code);
	}

	private long version(Long shipmentId) {
		return jdbc.queryForObject("SELECT version FROM shipments WHERE id = ?", Long.class, shipmentId);
	}

	private Row constraint(Long shipmentId, Long importerCompanyId) {
		List<Row> rows = jdbc.query("""
				SELECT transshipment_port, to_char(transshipment_arrival_deadline AT TIME ZONE 'UTC',
					'YYYY-MM-DD"T"HH24:MI:SS"Z"')
				FROM shipment_importer_constraints
				WHERE shipment_id = ? AND importer_company_id = ?
				""", (result, row) -> new Row(result.getString(1), result.getString(2)), shipmentId, importerCompanyId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	private static String deadlineBody(String deadline, String port) {
		String value = deadline == null ? "null" : "\"" + deadline + "\"";
		return "{\"transshipmentArrivalDeadline\":" + value + ",\"expectedTransshipmentPort\":\"" + port + "\"}";
	}

	private record Fixture(Long shipmentId, Long forwarderCompanyId, Long importerCompanyId, String forwarderToken,
			String importerToken) {
	}

	private record Row(String port, String deadline) {
	}
}
