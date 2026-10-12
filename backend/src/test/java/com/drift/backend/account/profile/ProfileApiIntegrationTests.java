package com.drift.backend.account.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.drift.backend.account.exception.InvalidProfileException;
import com.drift.backend.account.exception.ProfileForbiddenException;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.account.registration.PasswordPolicy;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProfileApiIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private Long companyId;
	private String email;

	@BeforeEach
	void account() {
		companyId = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'", Long.class);
		email = insert("Alice Tan", "FREIGHT_FORWARDER", companyId);
	}

	@Test
	void returnsTheCallersOwnProfileAndNothingSecret() throws Exception {
		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + token(email)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.length()").value(7))
				.andExpect(jsonPath("$.fullName").value("Alice Tan"))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"))
				.andExpect(jsonPath("$.company.id").value(companyId))
				.andExpect(jsonPath("$.company.code").value("HARBOURLINE_DEMO"))
				.andExpect(jsonPath("$.company.name").value("Harbourline Logistics (Demo)"))
				.andExpect(jsonPath("$.riskEmailNotificationsEnabled").value(false))
				.andExpect(jsonPath("$.minimumRiskNotificationLevel").value("HIGH"))
				.andExpect(jsonPath("$.password").doesNotExist())
				.andExpect(jsonPath("$.passwordHash").doesNotExist())
				.andExpect(jsonPath("$.token").doesNotExist());
	}

	@Test
	void updatesTheFullNameWithoutEndingTheSessionOrChangingIdentity() throws Exception {
		String bearer = token(email);

		mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"fullName":"  Nur Aisyah  ","riskEmailNotificationsEnabled":true,"minimumRiskNotificationLevel":"MEDIUM"}
						"""))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.fullName").value("Nur Aisyah"))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"))
				.andExpect(jsonPath("$.company.id").value(companyId))
				.andExpect(jsonPath("$.riskEmailNotificationsEnabled").value(true))
				.andExpect(jsonPath("$.minimumRiskNotificationLevel").value("MEDIUM"));

		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + bearer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fullName").value("Nur Aisyah"));
		String signedInAgain = token(email);
		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + signedInAgain))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fullName").value("Nur Aisyah"))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.riskEmailNotificationsEnabled").value(true))
				.andExpect(jsonPath("$.minimumRiskNotificationLevel").value("MEDIUM"));
	}

	@Test
	void rejectsAnEmptyOrOverlongNameWithoutLosingTheSavedName() throws Exception {
		String bearer = token(email);
		reject(bearer, "{\"fullName\":\"   \"}", InvalidProfileException.NAME_REQUIRED);
		reject(bearer, "{\"fullName\":\"" + "n".repeat(201) + "\"}", InvalidProfileException.NAME_TOO_LONG);

		mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"fullName\":\"" + "n".repeat(200) + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fullName").value("n".repeat(200)));
		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + bearer))
				.andExpect(jsonPath("$.fullName").value("n".repeat(200)));
	}

	@Test
	void changesThePasswordWithTheExistingPolicyAndEndsTheCurrentSession() throws Exception {
		String bearer = token(email);

		mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"currentPassword":"Example123","newPassword":"FreshPass1"}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());

		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + bearer))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content(login(email, "Example123")))
				.andExpect(status().isUnauthorized());
		String renewed = token(email, "FreshPass1");
		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + renewed))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fullName").value("Alice Tan"));
		assertThat(jdbc.queryForObject("SELECT session_generation FROM users WHERE email = ?", Long.class, email))
				.isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM information_schema.columns
				WHERE table_name = 'users' AND column_name = 'password_hash'
				""", Integer.class)).isEqualTo(1);
	}

	@Test
	void rejectsAWrongCurrentPasswordOrANonCompliantNewPasswordAndKeepsTheName() throws Exception {
		String bearer = token(email);
		reject(bearer, """
				{"fullName":"Should Stay","currentPassword":"WrongPass1","newPassword":"FreshPass1"}
				""", InvalidProfileException.CURRENT_INCORRECT);
		reject(bearer, """
				{"currentPassword":"Example123","newPassword":"password1"}
				""", PasswordPolicy.MESSAGE);
		reject(bearer, """
				{"currentPassword":"Example123","newPassword":"Has Space1"}
				""", "Password must not contain spaces");
		reject(bearer, """
				{"currentPassword":"Example123","newPassword":"%s"}
				""".formatted("Aa1" + "b".repeat(70)), "Password must be at most 72 bytes");
		reject(bearer, """
				{"newPassword":"FreshPass1"}
				""", InvalidProfileException.CURRENT_REQUIRED);
		reject(bearer, """
				{"currentPassword":"Example123"}
				""", InvalidProfileException.NEW_REQUIRED);

		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + bearer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fullName").value("Alice Tan"));
		assertThat(passwords.matches("Example123", jdbc.queryForObject(
				"SELECT password_hash FROM users WHERE email = ?", String.class, email))).isTrue();
	}

	@Test
	void storesTheNotificationChoiceWithoutSendingMailOrEndingTheSession() throws Exception {
		String bearer = token(email);

		mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"riskEmailNotificationsEnabled":true,"minimumRiskNotificationLevel":"CRITICAL"}
						"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.riskEmailNotificationsEnabled").value(true))
				.andExpect(jsonPath("$.minimumRiskNotificationLevel").value("CRITICAL"))
				.andExpect(jsonPath("$.email").value(email));

		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + bearer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.riskEmailNotificationsEnabled").value(true))
				.andExpect(jsonPath("$.minimumRiskNotificationLevel").value("CRITICAL"));
		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + token(email)))
				.andExpect(jsonPath("$.minimumRiskNotificationLevel").value("CRITICAL"));
		reject(bearer, "{\"minimumRiskNotificationLevel\":\"high\"}", InvalidProfileException.RISK_LEVEL);
		reject(bearer, "{\"minimumRiskNotificationLevel\":\"SEVERE\"}", InvalidProfileException.RISK_LEVEL);

		mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"riskEmailNotificationsEnabled\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.riskEmailNotificationsEnabled").value(false))
				.andExpect(jsonPath("$.minimumRiskNotificationLevel").value("CRITICAL"));
	}

	@Test
	void rejectsAttemptsToChangeEmailCompanyRoleOrPhoto() throws Exception {
		String bearer = token(email);
		reject(bearer, "{\"email\":\"" + email + "\"}", InvalidProfileException.LOCKED);
		reject(bearer, "{\"company\":{\"id\":" + companyId + "}}", InvalidProfileException.LOCKED);
		reject(bearer, "{\"companyId\":" + companyId + "}", InvalidProfileException.LOCKED);
		reject(bearer, "{\"organisation\":\"Harbourline\"}", InvalidProfileException.LOCKED);
		reject(bearer, "{\"organisationId\":" + companyId + "}", InvalidProfileException.LOCKED);
		reject(bearer, "{\"role\":\"ADMIN\"}", InvalidProfileException.LOCKED);
		reject(bearer, "{\"roleId\":1}", InvalidProfileException.LOCKED);
		reject(bearer, "{\"photo\":\"avatar.png\"}", InvalidProfileException.LOCKED);
		reject(bearer, "{\"profilePhoto\":\"avatar.png\"}", InvalidProfileException.LOCKED);
		reject(bearer, "{\"password\":\"FreshPass1\"}", InvalidProfileException.PASSWORD_FIELDS);

		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + bearer))
				.andExpect(jsonPath("$.email").value(email))
				.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"))
				.andExpect(jsonPath("$.company.id").value(companyId))
				.andExpect(jsonPath("$.fullName").value("Alice Tan"));
	}

	@Test
	void rejectsADifferentUserAndLeavesBothProfilesUntouched() throws Exception {
		String otherEmail = insert("Other Person", "IMPORTER", companyId);
		long otherId = ((Number) JsonPath.read(loginBody(otherEmail, "Example123"), "$.id")).longValue();
		String bearer = token(email);

		mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":" + otherId + ",\"fullName\":\"Taken Over\"}"))
				.andExpect(status().isForbidden())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(ProfileForbiddenException.MESSAGE))
				.andExpect(jsonPath("$.length()").value(1));

		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + bearer))
				.andExpect(jsonPath("$.fullName").value("Alice Tan"));
		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + token(otherEmail)))
				.andExpect(jsonPath("$.fullName").value("Other Person"))
				.andExpect(jsonPath("$.email").value(otherEmail))
				.andExpect(jsonPath("$.role").value("IMPORTER"));
		mvc.perform(get("/api/profile/" + otherId).header("Authorization", "Bearer " + bearer))
				.andExpect(status().isNotFound());
	}

	@Test
	void acceptsTheCallersOwnIdAndAnEmptyChange() throws Exception {
		long ownId = ((Number) JsonPath.read(loginBody(email, "Example123"), "$.id")).longValue();
		String bearer = token(email);

		mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.fullName").value("Alice Tan"))
				.andExpect(jsonPath("$.minimumRiskNotificationLevel").value("HIGH"));
		mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":" + ownId + ",\"fullName\":\"Alice Tan\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(ownId))
				.andExpect(jsonPath("$.fullName").value("Alice Tan"));
	}

	@Test
	void missingOrBrokenSessionAndMalformedBodyAreRejected() throws Exception {
		mvc.perform(get("/api/profile"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		mvc.perform(patch("/api/profile").contentType(MediaType.APPLICATION_JSON).content("{\"fullName\":\"X\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		mvc.perform(get("/api/profile").header("Authorization", "Bearer not-a-token"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));

		String bearer = token(email);
		mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(InvalidProfileException.MISSING));
		mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(InvalidProfileException.MISSING));
	}

	@Test
	void everyRoleCanReadAndUpdateItsOwnProfile() throws Exception {
		String importer = insert("Importer One", "IMPORTER", companyId);
		String manager = insert("Manager One", "LOGISTICS_MANAGER", companyId);
		String admin = insert("Admin One", "ADMIN", companyId);
		String unassigned = insert("No Company", "FREIGHT_FORWARDER", null);

		for (String accountEmail : new String[] { importer, manager, admin, unassigned }) {
			String bearer = token(accountEmail);
			mvc.perform(get("/api/profile").header("Authorization", "Bearer " + bearer))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.email").value(accountEmail))
					.andExpect(jsonPath("$.riskEmailNotificationsEnabled").value(false));
			mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"fullName\":\"Renamed " + accountEmail.substring(0, 8) + "\",\"minimumRiskNotificationLevel\":\"LOW\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.email").value(accountEmail))
					.andExpect(jsonPath("$.minimumRiskNotificationLevel").value("LOW"));
		}
		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + token(unassigned)))
				.andExpect(jsonPath("$.company").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.role").value("FREIGHT_FORWARDER"));
	}

	@Test
	void openApiDocumentsTheProfile() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/profile'].get.summary").value("View the signed-in profile"))
				.andExpect(jsonPath("$.paths['/api/profile'].get.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/profile'].patch.summary").value("Update the signed-in profile"))
				.andExpect(jsonPath("$.paths['/api/profile'].patch.security[0].bearerAuth").exists())
				.andExpect(jsonPath("$.paths['/api/profile'].patch.responses['400']").exists())
				.andExpect(jsonPath("$.paths['/api/profile'].patch.responses['401']").exists())
				.andExpect(jsonPath("$.paths['/api/profile'].patch.responses['403']").exists())
				.andExpect(jsonPath("$.components.schemas.Profile.properties.minimumRiskNotificationLevel").exists())
				.andExpect(jsonPath("$.components.schemas.Profile.properties.passwordHash").doesNotExist());
	}

	private void reject(String bearer, String body, String message) throws Exception {
		mvc.perform(patch("/api/profile").header("Authorization", "Bearer " + bearer)
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(message))
				.andExpect(jsonPath("$.length()").value(1));
	}

	private String insert(String fullName, String role, Long company) {
		String accountEmail = "cdg141-" + UUID.randomUUID() + "@example.com";
		if (company == null) {
			jdbc.update("""
					INSERT INTO users (full_name, email, password_hash, role_id)
					SELECT ?, ?, ?, id FROM roles WHERE code = ?
					""", fullName, accountEmail, passwords.encode("Example123"), role);
			return accountEmail;
		}
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT ?, ?, ?, id, ? FROM roles WHERE code = ?
				""", fullName, accountEmail, passwords.encode("Example123"), company, role);
		return accountEmail;
	}

	private String token(String accountEmail) throws Exception {
		return token(accountEmail, "Example123");
	}

	private String token(String accountEmail, String password) throws Exception {
		return JsonPath.read(loginBody(accountEmail, password), "$.token");
	}

	private String loginBody(String accountEmail, String password) throws Exception {
		MvcResult result = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content(login(accountEmail, password)))
				.andExpect(status().isOk())
				.andReturn();
		return result.getResponse().getContentAsString();
	}

	private static String login(String accountEmail, String password) {
		return """
				{"email":"%s","password":"%s"}
				""".formatted(accountEmail, password);
	}
}
