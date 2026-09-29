package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.shipment.exception.DuplicateShipmentReferenceException;
import com.drift.backend.shipment.exception.InvalidItineraryException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentCreationForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ShipmentCreationIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired jakarta.persistence.EntityManager entityManager;

	private String email;
	private Long companyId;
	private Long accountId;
	private String token;

	@BeforeEach
	void account() throws Exception {
		email = "cdg75-" + UUID.randomUUID() + "@example.com";
		companyId = companyId("HARBOURLINE_DEMO");
		createAccount(email, companyId);
		accountId = jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
		token = tokenFor(email);
	}

	@Test
	void createsShipmentForTheAuthenticatedUsersCompanyAndPersistsIt() throws Exception {
		MvcResult result = create(token, shipment("HBL-2026-001"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.id").isNumber())
				.andExpect(jsonPath("$.shipmentReference").value("HBL-2026-001"))
				.andExpect(jsonPath("$.origin").value("Shanghai, CN"))
				.andExpect(jsonPath("$.destination").value("Jakarta, ID"))
				.andExpect(jsonPath("$.transshipmentPort").value("Singapore"))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.connectionWindow.duration").value("1 day 4 hours"))
				.andExpect(jsonPath("$.connectionWindow.totalSeconds").value(100800))
				.andReturn();

		Number responseId = JsonPath.read(result.getResponse().getContentAsString(), "$.id");
		Long shipmentId = responseId.longValue();
		assertThat(jdbc.queryForMap("""
				SELECT company_id, created_by_user_id, shipment_reference, mother_vessel, feeder_vessel
				FROM shipments WHERE id = ?
				""", shipmentId))
				.containsEntry("company_id", companyId)
				.containsEntry("created_by_user_id", accountId)
				.containsEntry("shipment_reference", "HBL-2026-001")
				.containsEntry("mother_vessel", "MV Pacific Horizon")
				.containsEntry("feeder_vessel", "MV Strait Runner");
	}

	@Test
	void rejectsMissingRequiredFields() throws Exception {
		create(token, "{}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Shipment information is missing or invalid"))
				.andExpect(jsonPath("$.errors.shipmentReference").value("Shipment reference is required"))
				.andExpect(jsonPath("$.errors.origin").value("Origin is required"))
				.andExpect(jsonPath("$.errors.destination").value("Destination is required"))
				.andExpect(jsonPath("$.errors.transshipmentPort").value("Transshipment port is required"))
				.andExpect(jsonPath("$.errors.motherVessel").value("Mother vessel is required"))
				.andExpect(jsonPath("$.errors.plannedMotherArrivalAt").value("Planned mother-vessel arrival is required"))
				.andExpect(jsonPath("$.errors.feederVessel").value("Feeder vessel is required"))
				.andExpect(jsonPath("$.errors.plannedFeederDepartureAt").value("Planned feeder-vessel departure is required"));
		assertThat(countShipments()).isZero();
	}

	@Test
	void rejectsAFeederDepartureThatIsNotAfterMotherArrival() throws Exception {
		create(token, shipmentWithTimes("HBL-2026-002", "2026-10-15T08:00:00+08:00", "2026-10-15T08:00:00+08:00"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidItineraryException.MESSAGE))
				.andExpect(jsonPath("$.errors.plannedFeederDepartureAt").value(InvalidItineraryException.MESSAGE));
		assertThat(countShipments()).isZero();
	}

	@Test
	void rejectsDuplicateReferencesWithinTheSameCompanyIgnoringCase() throws Exception {
		create(token, shipment("HBL-2026-003")).andExpect(status().isCreated());

		String colleague = "cdg75-colleague-" + UUID.randomUUID() + "@example.com";
		createAccount(colleague, companyId);
		create(tokenFor(colleague), shipment("hbl-2026-003"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(DuplicateShipmentReferenceException.MESSAGE));
		assertThat(countShipments()).isEqualTo(1);
	}

	@Test
	void permitsTheSameReferenceForAnotherCompany() throws Exception {
		create(token, shipment("HBL-2026-004")).andExpect(status().isCreated());

		String otherEmail = "cdg75-other-" + UUID.randomUUID() + "@example.com";
		createAccount(otherEmail, companyId("STRAITS_FRESH_DEMO"));
		create(tokenFor(otherEmail), shipment("hbl-2026-004")).andExpect(status().isCreated());
		assertThat(countShipments()).isEqualTo(2);
	}

	@Test
	void rejectsAuthenticatedAccountsWithoutACompany() throws Exception {
		String unassigned = "cdg75-unassigned-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role)
				VALUES ('Unassigned User', ?, ?, 'FREIGHT_FORWARDER')
				""", unassigned, passwords.encode("Example123"));

		create(tokenFor(unassigned), shipment("HBL-2026-005"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentCreationForbiddenException.MESSAGE));
		assertThat(countShipments()).isZero();
	}

	@Test
	void requiresAnAuthenticatedSession() throws Exception {
		mvc.perform(post("/api/shipments").contentType(MediaType.APPLICATION_JSON).content(shipment("HBL-2026-006")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	@Test
	void listsOnlyTheCallersCompanyNewestFirst() throws Exception {
		create(token, shipment("HBL-OLDER")).andExpect(status().isCreated());
		create(token, shipment("HBL-NEWER")).andExpect(status().isCreated());
		jdbc.update("UPDATE shipments SET created_at = created_at - INTERVAL '1 hour' WHERE shipment_reference = 'HBL-OLDER'");

		String otherEmail = "cdg56-other-" + UUID.randomUUID() + "@example.com";
		createAccount(otherEmail, companyId("STRAITS_FRESH_DEMO"));
		create(tokenFor(otherEmail), shipment("HBL-OTHER")).andExpect(status().isCreated());

		list(token)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-NEWER"))
				.andExpect(jsonPath("$[1].shipmentReference").value("HBL-OLDER"))
				.andExpect(jsonPath("$[0].connectionWindow.duration").value("1 day 4 hours"))
				.andExpect(jsonPath("$[1].connectionWindow.duration").value("1 day 4 hours"));
	}

	@Test
	void calculatesTheConnectionWindowFromTheStoredScheduleAndRecalculatesWhenATimeChanges() throws Exception {
		create(token, shipmentWithTimes("HBL-WINDOW", "2026-10-15T20:00:00+08:00", "2026-10-16T02:30:00Z"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.connectionWindow.duration").value("14 hours 30 minutes"))
				.andExpect(jsonPath("$.connectionWindow.totalSeconds").value(52200));

		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name = 'shipments' AND column_name = 'connection_window'
				""", Integer.class)).isZero();

		jdbc.update("""
				UPDATE shipments SET planned_feeder_departure_at = '2026-10-16T04:30:00Z'
				WHERE shipment_reference = 'HBL-WINDOW'
				""");
		entityManager.clear();
		list(token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-WINDOW"))
				.andExpect(jsonPath("$[0].connectionWindow.duration").value("16 hours 30 minutes"))
				.andExpect(jsonPath("$[0].connectionWindow.totalSeconds").value(59400));

		jdbc.update("""
				UPDATE shipments SET planned_mother_arrival_at = '2026-10-15T22:00:00+08:00'
				WHERE shipment_reference = 'HBL-WINDOW'
				""");
		entityManager.clear();
		list(token)
				.andExpect(jsonPath("$[0].connectionWindow.duration").value("14 hours 30 minutes"))
				.andExpect(jsonPath("$[0].connectionWindow.totalSeconds").value(52200));
	}

	@Test
	void returnsAnEmptyListWhenTheCompanyHasNoShipments() throws Exception {
		list(token)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void rejectsListingForAnInactiveCompany() throws Exception {
		jdbc.update("UPDATE companies SET active = FALSE WHERE id = ?", companyId);
		entityManager.clear();
		list(token)
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentAccessForbiddenException.MESSAGE));
		detail(token, "1")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(ShipmentAccessForbiddenException.MESSAGE));
	}

	@Test
	void listsTheCompanysShipmentsForAnImporterColleague() throws Exception {
		create(token, shipment("HBL-2026-006")).andExpect(status().isCreated());

		String importer = "cdg24-importer-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role, company_id)
				VALUES ('Ivan Lim', ?, ?, 'IMPORTER', ?)
				""", importer, passwords.encode("Example123"), companyId);

		list(tokenFor(importer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].shipmentReference").value("HBL-2026-006"))
				.andExpect(jsonPath("$[0].transshipmentPort").value("Singapore"))
				.andExpect(jsonPath("$[0].connectionWindow.duration").value("1 day 4 hours"));
	}

	@Test
	void returnsOneOfTheCompanysShipments() throws Exception {
		MvcResult created = create(token, shipment("HBL-2026-007")).andExpect(status().isCreated()).andReturn();
		Number shipmentId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

		detail(token, shipmentId.toString())
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.id").value(shipmentId.longValue()))
				.andExpect(jsonPath("$.shipmentReference").value("HBL-2026-007"))
				.andExpect(jsonPath("$.transshipmentPort").value("Singapore"))
				.andExpect(jsonPath("$.feederVessel").value("MV Strait Runner"))
				.andExpect(jsonPath("$.connectionWindow").doesNotExist());
	}

	@Test
	void hidesAnotherCompanysShipmentAndUnknownIds() throws Exception {
		String otherEmail = "cdg24-other-" + UUID.randomUUID() + "@example.com";
		createAccount(otherEmail, companyId("STRAITS_FRESH_DEMO"));
		MvcResult created = create(tokenFor(otherEmail), shipment("HBL-2026-008")).andExpect(status().isCreated()).andReturn();
		Number otherShipmentId = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

		detail(token, otherShipmentId.toString())
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		detail(token, "999999999")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
		detail(token, "not-a-number")
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(ShipmentNotFoundException.MESSAGE));
	}

	@Test
	void rejectsListingAndDetailWithoutASession() throws Exception {
		mvc.perform(get("/api/shipments"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		mvc.perform(get("/api/shipments/1"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	private org.springframework.test.web.servlet.ResultActions detail(String bearerToken, String shipmentId) throws Exception {
		return mvc.perform(get("/api/shipments/" + shipmentId)
				.header("Authorization", "Bearer " + bearerToken));
	}

	private org.springframework.test.web.servlet.ResultActions list(String bearerToken) throws Exception {
		return mvc.perform(get("/api/shipments")
				.header("Authorization", "Bearer " + bearerToken));
	}

	private org.springframework.test.web.servlet.ResultActions create(String bearerToken, String body) throws Exception {
		return mvc.perform(post("/api/shipments")
				.header("Authorization", "Bearer " + bearerToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body));
	}

	private void createAccount(String accountEmail, Long accountCompanyId) {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role, company_id)
				VALUES ('Alice Tan', ?, ?, 'FREIGHT_FORWARDER', ?)
				""", accountEmail, passwords.encode("Example123"), accountCompanyId);
	}

	private String tokenFor(String accountEmail) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\",\"password\":\"Example123\"}".formatted(accountEmail)))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private int countShipments() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM shipments", Integer.class);
	}

	private static String shipment(String reference) {
		return shipmentWithTimes(reference, "2026-10-15T08:00:00+08:00", "2026-10-16T12:00:00+08:00");
	}

	private static String shipmentWithTimes(String reference, String motherArrival, String feederDeparture) {
		return """
				{
				  "shipmentReference": "%s",
				  "origin": "Shanghai, CN",
				  "destination": "Jakarta, ID",
				  "transshipmentPort": "Singapore",
				  "motherVessel": "MV Pacific Horizon",
				  "plannedMotherArrivalAt": "%s",
				  "feederVessel": "MV Strait Runner",
				  "plannedFeederDepartureAt": "%s"
				}
				""".formatted(reference, motherArrival, feederDeparture);
	}
}
