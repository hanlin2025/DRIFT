package com.drift.backend.account.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.admin.exception.AccountAlreadyActiveException;
import com.drift.backend.account.admin.exception.AssignedUserNotFoundException;
import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.admin.exception.InvalidRemovalException;
import com.drift.backend.account.admin.exception.StaleAccountVersionException;
import com.drift.backend.account.exception.InvalidCredentialsException;
import com.drift.backend.account.exception.SessionEndedException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountReactivationIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired EntityManager entityManager;
	@Autowired UserAccountRepository users;
	@Autowired PlatformTransactionManager transactions;

	private String adminEmail;
	private String adminToken;
	private Long harbourlineId;

	@BeforeEach
	void administrator() throws Exception {
		harbourlineId = companyId("HARBOURLINE_DEMO");
		adminEmail = createUser("Admin User", "ADMIN", harbourlineId);
		adminToken = tokenFor(adminEmail);
	}

	@Test
	void reactivatesTheSameAccountAndLetsTheExistingPasswordSignIn() throws Exception {
		String email = createUser("Alice Tan", "FREIGHT_FORWARDER", harbourlineId);
		Long userId = userId(email);
		String hash = passwordHash(userId);
		String previousToken = tokenFor(email);
		remove(adminToken, userId).andExpect(status().isOk());

		login(email).andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(InvalidCredentialsException.MESSAGE));
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + previousToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));

		Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
		reactivate(adminToken, userId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(userId))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.fullName").value("Alice Tan"))
				.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"))
				.andExpect(jsonPath("$.organisation.id").value(harbourlineId))
				.andExpect(jsonPath("$.organisation.code").value("HARBOURLINE_DEMO"))
				.andExpect(jsonPath("$.status").value("ACTIVE"))
				.andExpect(jsonPath("$.token").doesNotExist());
		Instant after = Instant.now();

		assertThat(statusOf(userId)).isEqualTo("ACTIVE");
		assertThat(passwordHash(userId)).isEqualTo(hash);
		assertThat(roleOf(userId)).isEqualTo("FREIGHT_FORWARDER");
		assertThat(companyOf(userId)).isEqualTo(harbourlineId);
		assertThat(generation(userId)).isEqualTo(1L);
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + previousToken))
				.andExpect(status().isUnauthorized());

		MvcResult signedIn = login(email)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(userId))
				.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"))
				.andExpect(jsonPath("$.company.id").value(harbourlineId))
				.andExpect(jsonPath("$.company.code").value("HARBOURLINE_DEMO"))
				.andReturn();
		String freshToken = JsonPath.read(signedIn.getResponse().getContentAsString(), "$.token");
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + freshToken))
				.andExpect(status().isOk());

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT * FROM account_status_audits WHERE target_user_id = ? AND new_status = 'ACTIVE'", userId);
		assertThat(row.get("operator_user_id")).isEqualTo(userId(adminEmail));
		assertThat(row.get("target_user_id")).isEqualTo(userId);
		assertThat(row.get("organisation_id")).isEqualTo(harbourlineId);
		assertThat(row.get("previous_status")).isEqualTo("REMOVED");
		assertThat(((Timestamp) row.get("recorded_at")).toInstant()).isBetween(before, after);
		assertThat(reactivationAudits(userId)).isEqualTo(1);

		reactivate(adminToken, userId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(AccountAlreadyActiveException.MESSAGE));
		assertThat(statusOf(userId)).isEqualTo("ACTIVE");
		assertThat(passwordHash(userId)).isEqualTo(hash);
		assertThat(generation(userId)).isEqualTo(1L);
		assertThat(reactivationAudits(userId)).isEqualTo(1);
	}

	@Test
	void rejectsAnAccountThatIsAlreadyActive() throws Exception {
		Long userId = userId(createUser("Still Active", "IMPORTER", harbourlineId));

		reactivate(adminToken, userId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(AccountAlreadyActiveException.MESSAGE));

		assertThat(statusOf(userId)).isEqualTo("ACTIVE");
		assertThat(generation(userId)).isZero();
		assertThat(reactivationAudits(userId)).isZero();
	}

	@Test
	void reportsAnUnknownUserAsNotFound() throws Exception {
		reactivate(adminToken, 999999999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(AssignedUserNotFoundException.MESSAGE));
		mvc.perform(patch("/api/admin/users/not-a-number/reactivate").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(AssignedUserNotFoundException.MESSAGE));
	}

	@Test
	void reportsAnotherCompanysUserAsNotFoundAndLeavesItRemoved() throws Exception {
		Long userId = userId(createUser("Other Company", "IMPORTER", companyId("STRAITS_FRESH_DEMO")));
		remove(tokenFor(createUser("Other Admin", "ADMIN", companyId("STRAITS_FRESH_DEMO"))), userId)
				.andExpect(status().isOk());

		reactivate(adminToken, userId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(AssignedUserNotFoundException.MESSAGE));

		assertThat(statusOf(userId)).isEqualTo("REMOVED");
		assertThat(reactivationAudits(userId)).isZero();
	}

	@Test
	void reportsAUserWithoutACompanyAsNotFound() throws Exception {
		Long userId = userId(createUser("No Company", "IMPORTER", null));
		jdbc.update("UPDATE users SET status = 'REMOVED' WHERE id = ?", userId);
		entityManager.clear();

		reactivate(adminToken, userId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(AssignedUserNotFoundException.MESSAGE));

		assertThat(statusOf(userId)).isEqualTo("REMOVED");
		assertThat(reactivationAudits(userId)).isZero();
	}

	@Test
	void rejectsAnAdministratorWhoseCompanyIsInactive() throws Exception {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES ('CLOSING_CO', 'Closing Company', true)");
		Long closingId = companyId("CLOSING_CO");
		String closingAdmin = createUser("Closing Admin", "ADMIN", closingId);
		Long closedUser = userId(createUser("Closed Colleague", "IMPORTER", closingId));
		remove(tokenFor(closingAdmin), closedUser).andExpect(status().isOk());
		jdbc.update("UPDATE companies SET active = false WHERE id = ?", closingId);
		entityManager.clear();

		reactivate(tokenFor(closingAdmin), closedUser)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidRemovalException.ORGANISATION));
		assertThat(statusOf(closedUser)).isEqualTo("REMOVED");
		assertThat(reactivationAudits(closedUser)).isZero();
	}

	@Test
	void forbidsRolesOtherThanAdministrator() throws Exception {
		Long userId = removedUser("Target User", harbourlineId);

		for (String role : new String[] { "IMPORTER", "FREIGHT_FORWARDER", "LOGISTICS_MANAGER" }) {
			String token = tokenFor(createUser("Not Admin " + role, role, harbourlineId));
			reactivate(token, userId)
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.message").value(AssignmentForbiddenException.MESSAGE));
		}

		assertThat(statusOf(userId)).isEqualTo("REMOVED");
		assertThat(reactivationAudits(userId)).isZero();
	}

	@Test
	void requiresASession() throws Exception {
		Long userId = removedUser("Target User", harbourlineId);

		mvc.perform(patch("/api/admin/users/" + userId + "/reactivate"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));

		assertThat(statusOf(userId)).isEqualTo("REMOVED");
		assertThat(reactivationAudits(userId)).isZero();
	}

	@Test
	void documentsTheReactivationApi() throws Exception {
		String path = "$.paths['/api/admin/users/{userId}/reactivate'].patch";
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(path + ".summary").value("Reactivate a removed user account"))
				.andExpect(jsonPath(path + ".security[0].bearerAuth").exists())
				.andExpect(jsonPath(path + ".responses['200'].content['application/json'].schema.$ref")
						.value("#/components/schemas/AdminUser"))
				.andExpect(jsonPath(path + ".responses['400'].content['application/json'].examples['Organisation missing or inactive'].value.message")
						.value(InvalidRemovalException.ORGANISATION))
				.andExpect(jsonPath(path + ".responses['401'].content['application/json'].example.message")
						.value(SessionEndedException.MESSAGE))
				.andExpect(jsonPath(path + ".responses['403'].content['application/json'].example.message")
						.value(AssignmentForbiddenException.MESSAGE))
				.andExpect(jsonPath(path + ".responses['404'].content['application/json'].example.message")
						.value(AssignedUserNotFoundException.MESSAGE))
				.andExpect(jsonPath(path + ".responses['409'].content['application/json'].examples['Already active'].value.message")
						.value(AccountAlreadyActiveException.MESSAGE))
				.andExpect(jsonPath(path + ".responses['409'].content['application/json'].examples['Changed concurrently'].value.message")
						.value(StaleAccountVersionException.MESSAGE));
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void overlappingReactivationsProduceOneActiveAccountAndOneAuditRow() throws Exception {
		Map<String, Long> countsBefore = committedRowCounts();
		List<Long> committed = new ArrayList<>(List.of(userId(adminEmail)));
		ExecutorService otherRequest = Executors.newSingleThreadExecutor();
		try {
			Long target = userId(createUser("Raced User", "IMPORTER", harbourlineId));
			committed.add(target);
			remove(adminToken, target).andExpect(status().isOk());
			long generationBefore = generation(target);
			String hashBefore = passwordHash(target);
			String roleBefore = roleOf(target);
			Long companyBefore = companyOf(target);

			MvcResult[] results = new TransactionTemplate(transactions).execute(tx -> {
				try {
					users.findById(target).orElseThrow();
					MvcResult winner = otherRequest.submit(() -> reactivate(adminToken, target).andReturn()).get();
					MvcResult loser = reactivate(adminToken, target).andReturn();
					tx.setRollbackOnly();
					return new MvcResult[] { winner, loser };
				} catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			});

			assertThat(results[0].getResponse().getStatus()).isEqualTo(200);
			assertThat(results[1].getResponse().getStatus()).isEqualTo(409);
			assertThat((String) JsonPath.read(results[1].getResponse().getContentAsString(), "$.message"))
					.isEqualTo(StaleAccountVersionException.MESSAGE);
			assertThat(statusOf(target)).isEqualTo("ACTIVE");
			assertThat(generation(target)).isEqualTo(generationBefore);
			assertThat(passwordHash(target)).isEqualTo(hashBefore);
			assertThat(roleOf(target)).isEqualTo(roleBefore);
			assertThat(companyOf(target)).isEqualTo(companyBefore);
			assertThat(reactivationAudits(target)).isEqualTo(1);
		} finally {
			otherRequest.shutdownNow();
			deleteCommitted(committed);
		}
		assertThat(committedRowCounts()).isEqualTo(withoutOneUser(countsBefore));
	}

	private Long removedUser(String name, Long company) throws Exception {
		Long userId = userId(createUser(name, "IMPORTER", company));
		remove(adminToken, userId).andExpect(status().isOk());
		return userId;
	}

	private org.springframework.test.web.servlet.ResultActions reactivate(String token, Long userId) throws Exception {
		return mvc.perform(patch("/api/admin/users/" + userId + "/reactivate").header("Authorization", "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions remove(String token, Long userId) throws Exception {
		return mvc.perform(patch("/api/admin/users/" + userId + "/remove").header("Authorization", "Bearer " + token));
	}

	private org.springframework.test.web.servlet.ResultActions login(String email) throws Exception {
		return mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"Example123\"}"));
	}

	private String createUser(String name, String role, Long company) {
		String email = "cdg163-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ?
				FROM roles WHERE code = ?
				""", name, email, passwords.encode("Example123"), company, role);
		entityManager.clear();
		return email;
	}

	private String tokenFor(String email) throws Exception {
		MvcResult login = login(email).andExpect(status().isOk()).andReturn();
		return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
	}

	private Long userId(String email) {
		return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}

	private String statusOf(Long userId) {
		return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);
	}

	private long generation(Long userId) {
		return jdbc.queryForObject("SELECT session_generation FROM users WHERE id = ?", Long.class, userId);
	}

	private String passwordHash(Long userId) {
		return jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
	}

	private String roleOf(Long userId) {
		return jdbc.queryForObject("""
				SELECT roles.code FROM users JOIN roles ON roles.id = users.role_id WHERE users.id = ?
				""", String.class, userId);
	}

	private Long companyOf(Long userId) {
		return jdbc.queryForObject("SELECT company_id FROM users WHERE id = ?", Long.class, userId);
	}

	private int reactivationAudits(Long userId) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM account_status_audits
				WHERE target_user_id = ? AND previous_status = 'REMOVED' AND new_status = 'ACTIVE'
				""", Integer.class, userId);
	}

	private void deleteCommitted(List<Long> userIds) {
		for (Long id : userIds) {
			jdbc.update("DELETE FROM account_status_audits WHERE operator_user_id = ? OR target_user_id = ?", id, id);
			jdbc.update("DELETE FROM user_assignment_audits WHERE operator_user_id = ? OR target_user_id = ?", id, id);
		}
		for (Long id : userIds) {
			jdbc.update("DELETE FROM users WHERE id = ?", id);
		}
	}

	private Map<String, Long> committedRowCounts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : List.of("users", "companies", "invitations", "user_assignment_audits", "account_status_audits")) {
			counts.put(table, jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
		}
		return counts;
	}

	private static Map<String, Long> withoutOneUser(Map<String, Long> counts) {
		Map<String, Long> expected = new LinkedHashMap<>(counts);
		expected.put("users", counts.get("users") - 1);
		return expected;
	}
}
