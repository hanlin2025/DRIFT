package com.drift.backend.account.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.drift.backend.account.exception.EmailNotVerifiedException;
import com.drift.backend.account.exception.InvalidCredentialsException;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.account.registration.PasswordPolicy;
import com.drift.backend.organisation.exception.DuplicateOrganisationException;
import com.drift.backend.organisation.exception.InvalidOrganisationException;
import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CompanySignupIntegrationTests {

	private static final String PASSWORD = "Str0ngPass";
	private static final String ACCEPTED = "{\"message\":\"Check your email to verify your account before signing in.\"}";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;

	private final List<String> emails = new ArrayList<>();
	private final List<String> codes = new ArrayList<>();
	private String code;
	private String email;

	@BeforeEach
	void names() {
		code = track(SignupFixtures.uniqueCode("CDG361_"));
		email = trackEmail(SignupFixtures.uniqueEmail("cdg361"));
	}

	@AfterEach
	void cleanup() {
		SignupFixtures.cleanup(jdbc, emails, codes);
	}

	@Test
	void createsAnInactiveCompanyAPendingAdministratorATokenAndAnAuditRowInOneRequest() throws Exception {
		Instant before = Instant.now();
		signup(code.toLowerCase(), "  Pacific Gate Imports  ", "  Alex Tan  ", "  " + email.toUpperCase() + " ", PASSWORD)
				.andExpect(status().isAccepted())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(content().json(ACCEPTED, org.springframework.test.json.JsonCompareMode.STRICT));

		Map<String, Object> company = jdbc.queryForMap("SELECT id, name, active, created_by_user_id FROM companies WHERE code = ?", code);
		Map<String, Object> user = jdbc.queryForMap("""
				SELECT users.id, users.full_name, users.status, users.company_id, users.password_hash, roles.code AS role
				FROM users JOIN roles ON roles.id = users.role_id WHERE users.email = ?
				""", email);
		assertThat(company.get("name")).isEqualTo("Pacific Gate Imports");
		assertThat(company.get("active")).isEqualTo(false);
		assertThat(company.get("created_by_user_id")).isEqualTo(user.get("id"));
		assertThat(user.get("full_name")).isEqualTo("Alex Tan");
		assertThat(user.get("status")).isEqualTo("PENDING_VERIFICATION");
		assertThat(user.get("role")).isEqualTo("ADMIN");
		assertThat(user.get("company_id")).isEqualTo(company.get("id"));
		assertThat(passwords.matches(PASSWORD, (String) user.get("password_hash"))).isTrue();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE company_id = ?", Long.class, company.get("id"))).isEqualTo(1L);

		List<Map<String, Object>> tokens = jdbc.queryForList(
				"SELECT token_hash, expires_at, used_at FROM email_verification_tokens WHERE user_id = ?", user.get("id"));
		assertThat(tokens).hasSize(1);
		assertThat((String) tokens.get(0).get("token_hash")).matches("[0-9a-f]{64}");
		assertThat(tokens.get(0).get("used_at")).isNull();
		Instant expiresAt = ((Timestamp) tokens.get(0).get("expires_at")).toInstant();
		assertThat(expiresAt).isBetween(before.plus(Duration.ofHours(24)).minusSeconds(5), Instant.now().plus(Duration.ofHours(24)));

		assertThat(jdbc.queryForList("SELECT event FROM company_signup_audits WHERE user_id = ? AND company_id = ?",
				String.class, user.get("id"), company.get("id"))).containsExactly("REGISTERED");
	}

	static Stream<Arguments> invalidFields() {
		String longName = "N".repeat(201);
		return Stream.of(
				Arguments.of("companyCode", null, "Pacific Gate", "Alex Tan", "a@example.com", PASSWORD, InvalidOrganisationException.CODE),
				Arguments.of("companyCode", "   ", "Pacific Gate", "Alex Tan", "a@example.com", PASSWORD, InvalidOrganisationException.CODE),
				Arguments.of("companyCode", "1PACIFIC", "Pacific Gate", "Alex Tan", "a@example.com", PASSWORD, InvalidOrganisationException.CODE),
				Arguments.of("companyCode", "PACIFIC-GATE", "Pacific Gate", "Alex Tan", "a@example.com", PASSWORD, InvalidOrganisationException.CODE),
				Arguments.of("companyCode", "P" + "A".repeat(50), "Pacific Gate", "Alex Tan", "a@example.com", PASSWORD, InvalidOrganisationException.CODE),
				Arguments.of("companyName", "PACIFIC", "", "Alex Tan", "a@example.com", PASSWORD, InvalidOrganisationException.NAME),
				Arguments.of("companyName", "PACIFIC", longName, "Alex Tan", "a@example.com", PASSWORD, InvalidOrganisationException.NAME),
				Arguments.of("companyName", "PACIFIC", "Pacific\u0001Gate", "Alex Tan", "a@example.com", PASSWORD, InvalidOrganisationException.NAME),
				Arguments.of("fullName", "PACIFIC", "Pacific Gate", " ", "a@example.com", PASSWORD, "Full name is required"),
				Arguments.of("fullName", "PACIFIC", "Pacific Gate", longName, "a@example.com", PASSWORD, "Full name must be at most 200 characters"),
				Arguments.of("email", "PACIFIC", "Pacific Gate", "Alex Tan", "", PASSWORD, "Email is required"),
				Arguments.of("email", "PACIFIC", "Pacific Gate", "Alex Tan", "not-an-email", PASSWORD, "Email must be a valid email address"),
				Arguments.of("email", "PACIFIC", "Pacific Gate", "Alex Tan", "a".repeat(310) + "@example.com", PASSWORD, "Email must be at most 320 characters"),
				Arguments.of("password", "PACIFIC", "Pacific Gate", "Alex Tan", "a@example.com", "", "Password is required"));
	}

	@ParameterizedTest(name = "{0}: {6}")
	@MethodSource("invalidFields")
	void rejectsAnInvalidFieldWithItsErrorKeyAndPersistsNothing(String field, String companyCode, String companyName,
			String fullName, String address, String password, String message) throws Exception {
		String uniqueCode = companyCode == null || companyCode.isBlank() || !companyCode.equals("PACIFIC") ? companyCode : code;
		String uniqueEmail = address.equals("a@example.com") ? email : address;
		signup(uniqueCode, companyName, fullName, uniqueEmail, password)
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(CompanySignupExceptionHandler.INVALID))
				// An over-long email can also fail the format check, and either message may be reported first.
				.andExpect(jsonPath("$.errors." + field).value(message.contains("320")
						? org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.equalTo(message),
								org.hamcrest.Matchers.equalTo("Email must be a valid email address"))
						: org.hamcrest.Matchers.equalTo(message)));

		assertNothingPersisted();
		if (uniqueCode != null) {
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE UPPER(code) = UPPER(TRIM(?))", Long.class, uniqueCode))
					.isZero();
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = LOWER(TRIM(?))", Long.class, uniqueEmail))
				.isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "short1A", "alllowercase1", "ALLUPPERCASE1", "NoDigitsHere", "Has Space1" })
	void rejectsAPasswordThatBreaksThePolicy(String password) throws Exception {
		signup(code, "Pacific Gate", "Alex Tan", email, password)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.password").exists())
				.andExpect(result -> assertThat(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.message"))
						.isEqualTo(JsonPath.<String>read(result.getResponse().getContentAsString(), "$.errors.password")));
		assertNothingPersisted();
	}

	@Test
	void reportsThePolicyMessageForAShortPassword() throws Exception {
		signup(code, "Pacific Gate", "Alex Tan", email, "Ab1")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value("Password must be at least 8 characters"))
				.andExpect(jsonPath("$.errors.password").value("Password must be at least 8 characters"));
		signup(code, "Pacific Gate", "Alex Tan", email, "abcdefgh1")
				.andExpect(jsonPath("$.errors.password").value(PasswordPolicy.MESSAGE));
		assertNothingPersisted();
	}

	@Test
	void rejectsUnreadableJson() throws Exception {
		mvc.perform(post("/api/register/company").contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(CompanySignupExceptionHandler.INVALID))
				.andExpect(jsonPath("$.errors").isEmpty());
	}

	@ParameterizedTest
	@ValueSource(strings = { "HARBOURLINE_DEMO", "harbourline_demo", " Harbourline_Demo " })
	void rejectsATakenCompanyCodeAndPersistsNothing(String taken) throws Exception {
		long companies = jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE code = 'HARBOURLINE_DEMO'", Long.class);
		signup(taken, "Pacific Gate", "Alex Tan", email, PASSWORD)
				.andExpect(status().isConflict())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(content().json("""
						{"message":"An organisation with this code already exists.",
						 "errors":{"companyCode":"An organisation with this code already exists."}}
						""", org.springframework.test.json.JsonCompareMode.STRICT))
				.andExpect(jsonPath("$.message").value(DuplicateOrganisationException.MESSAGE));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE code = 'HARBOURLINE_DEMO'", Long.class)).isEqualTo(companies);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isZero();
	}

	@ParameterizedTest
	@ValueSource(strings = { "ACTIVE", "PENDING_VERIFICATION", "REMOVED" })
	void answersAnExistingEmailWithTheSameAcceptedBodyAndCreatesNothing(String existingStatus) throws Exception {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id, status)
				SELECT 'Existing', ?, ?, id, (SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'), ?
				FROM roles WHERE code = 'IMPORTER'
				""", email, passwords.encode(PASSWORD), existingStatus);
		signup(code, "Pacific Gate", "Someone Else", email.toUpperCase(), PASSWORD)
				.andExpect(status().isAccepted())
				.andExpect(content().json(ACCEPTED, org.springframework.test.json.JsonCompareMode.STRICT));

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE LOWER(email) = ?", Long.class, email)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE code = ?", Long.class, code)).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = (SELECT id FROM users WHERE email = ?)
				""", Long.class, email)).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM company_signup_audits WHERE user_id = (SELECT id FROM users WHERE email = ?)
				""", Long.class, email)).isZero();
		assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE email = ?", String.class, email)).isEqualTo("Existing");
	}

	@Test
	void aTakenCodeAnswers409WhetherOrNotTheEmailIsRegistered() throws Exception {
		String registered = trackEmail(SignupFixtures.uniqueEmail("cdg361-known"));
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Known', ?, ?, id, (SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO') FROM roles WHERE code = 'IMPORTER'
				""", registered, passwords.encode(PASSWORD));
		long companies = jdbc.queryForObject("SELECT COUNT(*) FROM companies", Long.class);

		String forRegistered = signup("HARBOURLINE_DEMO", "Pacific Gate", "Alex Tan", registered, PASSWORD)
				.andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
		String forUnknown = signup("HARBOURLINE_DEMO", "Pacific Gate", "Alex Tan", email, PASSWORD)
				.andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();

		assertThat(forRegistered).isEqualTo(forUnknown);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies", Long.class)).isEqualTo(companies);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isZero();
		assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE email = ?", String.class, registered)).isEqualTo("Known");
	}

	@Test
	void aPendingEmailRepeatingItsOwnCodeIsNotTreatedAsATakenCode() throws Exception {
		signup(code, "Pacific Gate", "Alex Tan", email, PASSWORD).andExpect(status().isAccepted());
		signup(code.toLowerCase(), "Pacific Gate", "Alex Tan", email, PASSWORD)
				.andExpect(status().isAccepted())
				.andExpect(content().json(ACCEPTED, org.springframework.test.json.JsonCompareMode.STRICT));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE code = ?", Long.class, code)).isEqualTo(1L);
	}

	@Test
	void theSameRequestTwiceCreatesOneCompanyAndOneUser() throws Exception {
		for (int attempt = 0; attempt < 2; attempt++) {
			signup(code, "Pacific Gate", "Alex Tan", email, PASSWORD)
					.andExpect(status().isAccepted())
					.andExpect(content().json(ACCEPTED, org.springframework.test.json.JsonCompareMode.STRICT));
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE code = ?", Long.class, code)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = (SELECT id FROM users WHERE email = ?)
				""", Long.class, email)).isEqualTo(1L);
	}

	@Test
	void aPendingAdministratorCannotSignInButAWrongPasswordStaysGeneric() throws Exception {
		signup(code, "Pacific Gate", "Alex Tan", email, PASSWORD).andExpect(status().isAccepted());

		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isForbidden())
				.andExpect(header().doesNotExist("Set-Cookie"))
				.andExpect(content().json("{\"message\":\"" + EmailNotVerifiedException.MESSAGE
						+ "\",\"code\":\"EMAIL_NOT_VERIFIED\"}", org.springframework.test.json.JsonCompareMode.STRICT));
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"Wr0ngPass\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist("Set-Cookie"))
				.andExpect(jsonPath("$.message").value(InvalidCredentialsException.MESSAGE));
	}

	@Test
	void aSessionForAnAccountThatIsNoLongerActiveIsRejected() throws Exception {
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Signed In', ?, ?, id, (SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO')
				FROM roles WHERE code = 'IMPORTER'
				""", email, passwords.encode(PASSWORD));
		String token = login(email);
		mvc.perform(get("/api/session").header("Authorization", "Bearer " + token)).andExpect(status().isOk());

		jdbc.update("UPDATE users SET status = 'PENDING_VERIFICATION' WHERE email = ?", email);

		mvc.perform(get("/api/session").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.message").value(SessionEndedException.MESSAGE));
		mvc.perform(get("/api/profile").header("Authorization", "Bearer " + token))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void anInactiveCompanyIsHiddenFromOtherCompaniesAndAVerifiedAdministratorIsScopedToTheirOwn() throws Exception {
		signup(code, "Pacific Gate Imports", "Alex Tan", email, PASSWORD).andExpect(status().isAccepted());
		String otherAdmin = trackEmail(SignupFixtures.uniqueEmail("cdg361-other"));
		String otherCode = track(SignupFixtures.uniqueCode("CDG361O_"));
		jdbc.update("INSERT INTO companies (code, name, active) VALUES (?, 'Other Forwarding', true)", otherCode);
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Other Admin', ?, ?, id, (SELECT id FROM companies WHERE code = ?) FROM roles WHERE code = 'ADMIN'
				""", otherAdmin, passwords.encode(PASSWORD), otherCode);
		String forwarder = trackEmail(SignupFixtures.uniqueEmail("cdg361-ff"));
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Forwarder', ?, ?, id, (SELECT id FROM companies WHERE code = ?) FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", forwarder, passwords.encode(PASSWORD), otherCode);
		String otherToken = login(otherAdmin);
		String forwarderToken = login(forwarder);

		mvc.perform(get("/api/admin/invitation-organisations").header("Authorization", "Bearer " + otherToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[?(@.code == '" + code + "')]").isEmpty());
		mvc.perform(get("/api/organisations").param("type", "importer").param("name", "Pacific Gate")
				.header("Authorization", "Bearer " + forwarderToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.code == '" + code + "')]").isEmpty());

		// Simulates the verification step that Part 4 (CDG-363) adds.
		jdbc.update("UPDATE users SET status = 'ACTIVE' WHERE email = ?", email);
		jdbc.update("UPDATE companies SET active = true WHERE code = ?", code);
		String adminToken = login(email);

		mvc.perform(get("/api/session").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.company.code").value(code));
		mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].email").value(email));
		mvc.perform(get("/api/admin/organisations").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].code").value(code));
		mvc.perform(get("/api/shipments").header("Authorization", "Bearer " + adminToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));
		mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + otherToken))
				.andExpect(jsonPath("$[?(@.email == '" + email + "')]").isEmpty());
	}

	@Test
	void documentsTheSignupApi() throws Exception {
		String path = "$.paths['/api/register/company'].post";
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath(path + ".summary").value("Create a company and its first administrator"))
				.andExpect(jsonPath(path + ".security").doesNotExist())
				.andExpect(jsonPath(path + ".responses['202'].content['application/json'].example.message")
						.value(CompanySignupResponse.ACCEPTED))
				.andExpect(jsonPath(path + ".responses['409'].content['application/json'].example.errors.companyCode")
						.value(DuplicateOrganisationException.MESSAGE))
				.andExpect(jsonPath(path + ".responses['400'].content['application/json'].examples['Validation'].value.errors.companyCode")
						.value(InvalidOrganisationException.CODE));
	}

	@Test
	void redactsTheRequestInLogs() {
		assertThat(new CompanySignupRequest(code, "Pacific", "Alex", email, PASSWORD).toString())
				.doesNotContain(PASSWORD).doesNotContain(email);
	}

	private ResultActions signup(String companyCode, String companyName, String fullName, String address, String password)
			throws Exception {
		return mvc.perform(post("/api/register/company").contentType(MediaType.APPLICATION_JSON)
				.content(SignupFixtures.body(companyCode, companyName, fullName, address, password)));
	}

	private String login(String address) throws Exception {
		String body = mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + address + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$.token");
	}

	private void assertNothingPersisted() {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE code = ?", Long.class, code)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isZero();
	}

	private String track(String value) {
		codes.add(value);
		return value;
	}

	private String trackEmail(String value) {
		emails.add(value);
		return value;
	}
}
