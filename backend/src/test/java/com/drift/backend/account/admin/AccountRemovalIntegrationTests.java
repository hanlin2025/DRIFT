package com.drift.backend.account.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.dao.OptimisticLockingFailureException;
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

import com.drift.backend.account.admin.exception.AccountAlreadyRemovedException;
import com.drift.backend.account.admin.exception.AssignedUserNotFoundException;
import com.drift.backend.account.admin.exception.AssignmentForbiddenException;
import com.drift.backend.account.admin.exception.InvalidRemovalException;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.admin.exception.StaleAccountVersionException;
import com.jayway.jsonpath.JsonPath;

import jakarta.persistence.EntityManager;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AccountRemovalIntegrationTests {

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
	void marksTheAccountRemovedAndKeepsTheRow() throws Exception {
		String email = createUser("Alice Tan", "FREIGHT_FORWARDER", harbourlineId);
		Long userId = userId(email);

		remove(adminToken, userId)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(userId))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.status").value("REMOVED"));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId)).isEqualTo("REMOVED");
		assertThat(jdbc.queryForObject("SELECT session_generation FROM users WHERE id = ?", Long.class, userId)).isEqualTo(1L);
	}

	@Test
	void endsTheRemovedUsersExistingSession() throws Exception {
		String email = createUser("Pat Lim", "IMPORTER", harbourlineId);
		String previousToken = tokenFor(email);
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + previousToken))
				.andExpect(status().isOk());

		remove(adminToken, userId(email)).andExpect(status().isOk());

		mvc.perform(get("/api/session").header("Authorization", "Bearer " + previousToken))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
	}

	@Test
	void rejectsRemovingAnAlreadyRemovedAccount() throws Exception {
		Long userId = userId(createUser("Twice Removed", "IMPORTER", harbourlineId));

		remove(adminToken, userId).andExpect(status().isOk());
		remove(adminToken, userId)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.message").value(AccountAlreadyRemovedException.MESSAGE));

		assertThat(jdbc.queryForObject("SELECT session_generation FROM users WHERE id = ?", Long.class, userId)).isEqualTo(1L);
	}

	@Test
	void reportsAnUnknownUserAsNotFound() throws Exception {
		remove(adminToken, 999999999L)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(AssignedUserNotFoundException.MESSAGE));
	}

	@Test
	void reportsAnotherCompanysUserAsNotFoundAndLeavesItActive() throws Exception {
		Long userId = userId(createUser("Other Company", "IMPORTER", companyId("STRAITS_FRESH_DEMO")));

		remove(adminToken, userId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(AssignedUserNotFoundException.MESSAGE));

		assertUnchanged(userId);
	}

	@Test
	void reportsAUserWithoutACompanyAsNotFound() throws Exception {
		Long userId = userId(createUser("No Company", "IMPORTER", null));

		remove(adminToken, userId)
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value(AssignedUserNotFoundException.MESSAGE));

		assertUnchanged(userId);
	}

	@Test
	void rejectsRemovingTheAdministratorsOwnAccount() throws Exception {
		remove(adminToken, userId(adminEmail))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidRemovalException.OWN_ACCOUNT));

		assertUnchanged(userId(adminEmail));
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk());
	}

	@Test
	void rejectsAnAdministratorWhoseCompanyIsInactive() throws Exception {
		jdbc.update("INSERT INTO companies (code, name, active) VALUES ('CLOSED_CO', 'Closed Company', false)");
		Long closedId = companyId("CLOSED_CO");
		String closedAdminToken = tokenFor(createUser("Closed Admin", "ADMIN", closedId));
		Long userId = userId(createUser("Closed User", "IMPORTER", closedId));

		remove(closedAdminToken, userId)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidRemovalException.ORGANISATION));

		assertUnchanged(userId);
	}

	@Test
	void forbidsRolesOtherThanAdministrator() throws Exception {
		Long userId = userId(createUser("Target User", "IMPORTER", harbourlineId));

		for (String role : new String[] { "IMPORTER", "FREIGHT_FORWARDER", "LOGISTICS_MANAGER" }) {
			String token = tokenFor(createUser("Not Admin " + role, role, harbourlineId));
			remove(token, userId)
					.andExpect(status().isForbidden())
					.andExpect(jsonPath("$.message").value(AssignmentForbiddenException.MESSAGE));
		}

		assertUnchanged(userId);
	}

	@Test
	void requiresASession() throws Exception {
		Long userId = userId(createUser("Target User", "IMPORTER", harbourlineId));

		mvc.perform(patch("/api/admin/users/" + userId + "/remove"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));

		assertUnchanged(userId);
	}

	@Test
	void listsTheAccountStatus() throws Exception {
		String email = createUser("Listed User", "IMPORTER", harbourlineId);

		mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.email == '" + email + "')].status").value("ACTIVE"));
	}

	@Test
	void documentsTheRemovalApi() throws Exception {
		String path = "$.paths['/api/admin/users/{userId}/remove'].patch";
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(path + ".summary").value("Remove a user account"))
				.andExpect(jsonPath(path + ".security[0].bearerAuth").exists())
				.andExpect(jsonPath(path + ".responses['200'].content['application/json'].schema.$ref")
						.value("#/components/schemas/AdminUser"))
				.andExpect(jsonPath(path + ".responses['400'].content['application/json'].examples['Own account'].value.message")
						.value(InvalidRemovalException.OWN_ACCOUNT))
				.andExpect(jsonPath(path + ".responses['400'].content['application/json'].examples['Organisation missing or inactive'].value.message")
						.value(InvalidRemovalException.ORGANISATION))
				.andExpect(jsonPath(path + ".responses['401'].content['application/json'].example.message")
						.value(SessionEndedException.MESSAGE))
				.andExpect(jsonPath(path + ".responses['404'].content['application/json'].example.message")
						.value(AssignedUserNotFoundException.MESSAGE))
				.andExpect(jsonPath(path + ".responses['409'].content['application/json'].examples['Already removed'].value.message")
						.value(AccountAlreadyRemovedException.MESSAGE))
				.andExpect(jsonPath(path + ".responses['409'].content['application/json'].examples['Changed concurrently'].value.message")
						.value(StaleAccountVersionException.MESSAGE));
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void concurrentRemovalsOfTheSameAccountLetExactlyOneSucceed() throws Exception {
		Map<String, Long> countsBefore = committedRowCounts();
		List<Long> committed = new ArrayList<>(List.of(userId(adminEmail)));
		ExecutorService otherRequest = Executors.newSingleThreadExecutor();
		try {
			Long target = userId(createUser("Raced User", "IMPORTER", harbourlineId));
			committed.add(target);
			long generationBefore = generation(target);
			long versionBefore = version(target);

			MvcResult[] results = new TransactionTemplate(transactions).execute(tx -> {
				try {
					users.findById(target).orElseThrow();
					MvcResult winner = otherRequest.submit(() -> remove(adminToken, target).andReturn()).get();
					MvcResult loser = remove(adminToken, target).andReturn();
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
			assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, target)).isEqualTo("REMOVED");
			assertThat(generation(target)).isEqualTo(generationBefore + 1);
			assertThat(version(target)).isEqualTo(versionBefore + 1);
			assertThat(auditsFor(target)).isEqualTo(1);
		} finally {
			otherRequest.shutdownNow();
			deleteCommitted(committed);
		}
		assertThat(committedRowCounts()).isEqualTo(withoutOneUser(countsBefore));
	}

	@Test
	@Transactional(propagation = Propagation.NOT_SUPPORTED)
	void staleWriterCannotRestoreARemovedAccount() throws Exception {
		Map<String, Long> countsBefore = committedRowCounts();
		List<Long> committed = new ArrayList<>(List.of(userId(adminEmail)));
		ExecutorService otherRequest = Executors.newSingleThreadExecutor();
		try {
			Long target = userId(createUser("Stale User", "IMPORTER", harbourlineId));
			committed.add(target);
			long generationBefore = generation(target);
			String hashBefore = passwordHash(target);

			new TransactionTemplate(transactions).executeWithoutResult(tx -> {
				try {
					UserAccount stale = users.findById(target).orElseThrow();
					MvcResult removal = otherRequest.submit(() -> remove(adminToken, target).andReturn()).get();
					assertThat(removal.getResponse().getStatus()).isEqualTo(200);

					stale.replacePasswordHash(passwords.encode("Changed123"));
					assertThrows(OptimisticLockingFailureException.class, () -> users.saveAndFlush(stale));
					tx.setRollbackOnly();
				} catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			});

			assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, target)).isEqualTo("REMOVED");
			assertThat(generation(target)).isEqualTo(generationBefore + 1);
			assertThat(passwordHash(target)).isEqualTo(hashBefore);
			assertThat(auditsFor(target)).isEqualTo(1);
		} finally {
			otherRequest.shutdownNow();
			deleteCommitted(committed);
		}
		assertThat(committedRowCounts()).isEqualTo(withoutOneUser(countsBefore));
	}

	private long generation(Long userId) {
		return jdbc.queryForObject("SELECT session_generation FROM users WHERE id = ?", Long.class, userId);
	}

	private long version(Long userId) {
		return jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, userId);
	}

	private String passwordHash(Long userId) {
		return jdbc.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId);
	}

	private int auditsFor(Long userId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM account_status_audits WHERE target_user_id = ?", Integer.class, userId);
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

	private void deleteCommitted(List<Long> userIds) {
		for (Long id : userIds) {
			jdbc.update("DELETE FROM account_status_audits WHERE operator_user_id = ? OR target_user_id = ?", id, id);
			jdbc.update("DELETE FROM user_assignment_audits WHERE operator_user_id = ? OR target_user_id = ?", id, id);
		}
		for (Long id : userIds) {
			jdbc.update("DELETE FROM users WHERE id = ?", id);
		}
	}

	private void assertUnchanged(Long userId) {
		assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId)).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("SELECT session_generation FROM users WHERE id = ?", Long.class, userId)).isZero();
	}

	private org.springframework.test.web.servlet.ResultActions remove(String token, Long userId) throws Exception {
		return mvc.perform(patch("/api/admin/users/" + userId + "/remove").header("Authorization", "Bearer " + token));
	}

	private String createUser(String name, String role, Long company) {
		String email = "cdg155-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ?
				FROM roles WHERE code = ?
				""", name, email, passwords.encode("Example123"), company, role);
		entityManager.clear();
		return email;
	}

	private String tokenFor(String email) throws Exception {
		MvcResult login = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"Example123\"}"))
				.andExpect(status().isOk())
				.andReturn();
		return JsonPath.read(login.getResponse().getContentAsString(), "$.token");
	}

	private Long userId(String email) {
		return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
	}

	private Long companyId(String code) {
		return jdbc.queryForObject("SELECT id FROM companies WHERE code = ?", Long.class, code);
	}
}
