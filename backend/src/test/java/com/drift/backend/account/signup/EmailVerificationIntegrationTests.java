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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.drift.backend.account.exception.EmailNotVerifiedException;
import com.drift.backend.account.passwordreset.ResetTokens;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmailVerificationIntegrationTests.CapturingMail.class)
class EmailVerificationIntegrationTests {

	private static final String PASSWORD = "Str0ngPass";
	private static final String INVALID = """
			{"message":"This verification link is invalid or has expired. Please request a new one.",
			 "errors":{"token":"This verification link is invalid or has expired. Please request a new one."}}
			""";
	private static final String RESENT = "{\"message\":\"If this email is awaiting verification, a new link has been sent.\"}";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired CapturingEmailVerificationMailer mailer;
	@Autowired EmailVerificationService verifications;

	private final List<String> emails = new ArrayList<>();
	private final List<String> codes = new ArrayList<>();
	private String code;
	private String email;

	@BeforeEach
	void names() {
		mailer.reset();
		code = SignupFixtures.uniqueCode("CDG363_");
		email = SignupFixtures.uniqueEmail("cdg363");
		codes.add(code);
		emails.add(email);
	}

	@AfterEach
	void cleanup() {
		mailer.reset();
		SignupFixtures.cleanup(jdbc, emails, codes);
	}

	@Test
	void signupEmailsOneLinkWhoseTokenIsStoredOnlyAsAHash() throws Exception {
		signup(code, email).andExpect(status().isAccepted());

		List<CapturingEmailVerificationMailer.Sent> sent = mailer.sentTo(email);
		assertThat(sent).hasSize(1);
		assertThat(sent.get(0).link()).startsWith("http://localhost:5173/verify-email#token=");
		assertThat(sent.get(0).validFor()).isEqualTo(Duration.ofHours(24));
		String token = CapturingEmailVerificationMailer.tokenFrom(sent.get(0).link());
		assertThat(token).matches("[A-Za-z0-9_-]{43}");
		assertThat(jdbc.queryForList("SELECT token_hash FROM email_verification_tokens WHERE user_id = ?", String.class, userId()))
				.containsExactly(ResetTokens.hash(token))
				.doesNotContain(token);
	}

	@Test
	void aValidLinkActivatesTheAccountAndCompanyAndRecordsTheVerification() throws Exception {
		signup(code, email).andExpect(status().isAccepted());
		String token = onlyToken();

		verify(token)
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(content().json("{\"message\":\"Your email has been verified. You can now sign in.\"}", JsonCompareMode.STRICT));

		assertThat(jdbc.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email)).isEqualTo("ACTIVE");
		assertThat(jdbc.queryForObject("SELECT active FROM companies WHERE code = ?", Boolean.class, code)).isTrue();
		assertThat(jdbc.queryForList("SELECT event FROM company_signup_audits WHERE user_id = ? ORDER BY id", String.class, userId()))
				.containsExactly("REGISTERED", "EMAIL_VERIFIED");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = ? AND used_at IS NOT NULL",
				Long.class, userId())).isEqualTo(1L);
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.role").value("ADMIN"))
				.andExpect(jsonPath("$.company.code").value(code));
	}

	@Test
	void aReplayedUnknownOrMalformedLinkGetsTheSameInvalidResponse() throws Exception {
		signup(code, email).andExpect(status().isAccepted());
		String token = onlyToken();
		verify(token).andExpect(status().isOk());

		verify(token).andExpect(status().isBadRequest()).andExpect(content().json(INVALID, JsonCompareMode.STRICT));
		verify(ResetTokens.generate()).andExpect(status().isBadRequest()).andExpect(content().json(INVALID, JsonCompareMode.STRICT));
		verify("too-short").andExpect(status().isBadRequest()).andExpect(content().json(INVALID, JsonCompareMode.STRICT));
		verify("").andExpect(status().isBadRequest()).andExpect(content().json(INVALID, JsonCompareMode.STRICT));
		assertThat(jdbc.queryForList("SELECT event FROM company_signup_audits WHERE user_id = ?", String.class, userId()))
				.containsExactly("REGISTERED", "EMAIL_VERIFIED");
	}

	@Test
	void anExpiredLinkIsRejectedAndChangesNothing() throws Exception {
		signup(code, email).andExpect(status().isAccepted());
		String expired = ResetTokens.generate();
		jdbc.update("INSERT INTO email_verification_tokens (user_id, token_hash, expires_at) VALUES (?, ?, ?)",
				userId(), ResetTokens.hash(expired), Timestamp.from(Instant.now().minusSeconds(60)));

		verify(expired).andExpect(status().isBadRequest()).andExpect(content().json(INVALID, JsonCompareMode.STRICT));

		assertThat(jdbc.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email)).isEqualTo("PENDING_VERIFICATION");
		assertThat(jdbc.queryForObject("SELECT active FROM companies WHERE code = ?", Boolean.class, code)).isFalse();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = ? AND used_at IS NOT NULL",
				Long.class, userId())).isZero();
	}

	@Test
	void aLinkReplacedByAResendStopsWorking() throws Exception {
		signup(code, email).andExpect(status().isAccepted());
		String first = onlyToken();

		resend(email).andExpect(status().isOk()).andExpect(content().json(RESENT, JsonCompareMode.STRICT));
		List<CapturingEmailVerificationMailer.Sent> sent = mailer.sentTo(email);
		assertThat(sent).hasSize(2);
		String second = CapturingEmailVerificationMailer.tokenFrom(sent.get(1).link());

		verify(first).andExpect(status().isBadRequest()).andExpect(content().json(INVALID, JsonCompareMode.STRICT));
		verify(second).andExpect(status().isOk());
	}

	@Test
	void resendAnswersEveryEmailTheSameAndMailsOnlyAPendingAccount() throws Exception {
		signup(code, email).andExpect(status().isAccepted());
		String unknown = track(SignupFixtures.uniqueEmail("cdg363-unknown"));
		String active = track(existing("ACTIVE"));
		String removed = track(existing("REMOVED"));
		mailer.reset();

		for (String address : List.of(email, unknown, active, removed)) {
			resend(address.toUpperCase())
					.andExpect(status().isOk())
					.andExpect(header().string("Cache-Control", "no-store"))
					.andExpect(content().json(RESENT, JsonCompareMode.STRICT));
		}

		assertThat(mailer.sentTo(email)).hasSize(1);
		assertThat(mailer.sentTo(unknown)).isEmpty();
		assertThat(mailer.sentTo(active)).isEmpty();
		assertThat(mailer.sentTo(removed)).isEmpty();
		resend("not-an-email")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.message").value(EmailVerificationExceptionHandler.INVALID))
				.andExpect(jsonPath("$.errors.email").value("Email must be a valid email address"));
	}

	@Test
	void repeatingAPendingSignupForTheSameCompanySendsAFreshLink() throws Exception {
		signup(code, email).andExpect(status().isAccepted());
		String first = onlyToken();

		signup(code, email).andExpect(status().isAccepted());

		assertThat(mailer.sentTo(email)).hasSize(2);
		verify(first).andExpect(status().isBadRequest());
		signup(SignupFixtures.uniqueCode("CDG363X_"), email).andExpect(status().isAccepted());
		assertThat(mailer.sentTo(email)).hasSize(2);
	}

	@Test
	void aFailingMailerKeepsTheSignupAndResendWorksAfterwards() throws Exception {
		Logger logger = (Logger) LoggerFactory.getLogger(VerificationMailDispatcher.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		try {
			mailer.failDelivery(true);
			signup(code, email)
					.andExpect(status().isAccepted())
					.andExpect(content().json("{\"message\":\"Check your email to verify your account before signing in.\"}",
							JsonCompareMode.STRICT));
		} finally {
			logger.detachAppender(logs);
		}

		assertThat(jdbc.queryForObject("SELECT status FROM users WHERE email = ?", String.class, email)).isEqualTo("PENDING_VERIFICATION");
		assertThat(jdbc.queryForObject("SELECT active FROM companies WHERE code = ?", Boolean.class, code)).isFalse();
		assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("could not be sent"));
		assertThat(logs.list).allSatisfy(event -> assertThat(event.getFormattedMessage())
				.doesNotContain("#token=").doesNotContain(email));

		mailer.failDelivery(false);
		resend(email).andExpect(status().isOk());
		verify(onlyToken()).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("SELECT active FROM companies WHERE code = ?", Boolean.class, code)).isTrue();
	}

	@Test
	void aPendingAdministratorIsToldToVerifyBeforeSigningIn() throws Exception {
		signup(code, email).andExpect(status().isAccepted());
		mvc.perform(post("/api/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.message").value(EmailNotVerifiedException.MESSAGE));
	}

	@Test
	void twoConcurrentVerificationsWithOneLinkLetExactlyOneSucceed() throws Exception {
		signup(code, email).andExpect(status().isAccepted());
		String token = onlyToken();
		AtomicInteger verified = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		ExecutorService workers = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> runs = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				String address = "198.51.100." + (10 + i);
				runs.add(workers.submit(() -> {
					start.await();
					try {
						verifications.verify(new VerifyEmailRequest(token), address);
						verified.incrementAndGet();
					} catch (InvalidVerificationTokenException ex) {
						rejected.incrementAndGet();
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> run : runs) {
				run.get(30, TimeUnit.SECONDS);
			}
		} finally {
			workers.shutdownNow();
		}

		assertThat(verified.get()).isEqualTo(1);
		assertThat(rejected.get()).isEqualTo(1);
		assertThat(jdbc.queryForList("SELECT event FROM company_signup_audits WHERE user_id = ?", String.class, userId()))
				.containsExactlyInAnyOrder("REGISTERED", "EMAIL_VERIFIED");
	}

	@Test
	void documentsTheVerificationApi() throws Exception {
		mvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.paths['/api/auth/verify-email'].post.summary").value("Verify an email address with a verification link"))
				.andExpect(jsonPath("$.paths['/api/auth/verify-email'].post.responses['400'].content['application/json'].example.errors.token")
						.value(InvalidVerificationTokenException.MESSAGE))
				.andExpect(jsonPath("$.paths['/api/auth/resend-verification'].post.responses['200'].content['application/json'].example.message")
						.value(VerificationMessageResponse.RESENT))
				.andExpect(jsonPath("$.paths['/api/register/company'].post.responses['429'].content['application/json'].example.message")
						.value(SignupRateLimitException.MESSAGE));
	}

	private String onlyToken() {
		List<CapturingEmailVerificationMailer.Sent> sent = mailer.sentTo(email);
		assertThat(sent).isNotEmpty();
		return CapturingEmailVerificationMailer.tokenFrom(sent.get(sent.size() - 1).link());
	}

	private String existing(String status) {
		String address = SignupFixtures.uniqueEmail("cdg363-" + status.toLowerCase());
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id, status)
				SELECT 'Existing', ?, ?, id, (SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'), ?
				FROM roles WHERE code = 'IMPORTER'
				""", address, passwords.encode(PASSWORD), status);
		return address;
	}

	private String track(String address) {
		emails.add(address);
		return address;
	}

	private Long userId() {
		return jdbc.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
	}

	private ResultActions signup(String companyCode, String address) throws Exception {
		codes.add(companyCode);
		return mvc.perform(post("/api/register/company").contentType(MediaType.APPLICATION_JSON)
				.content(SignupFixtures.body(companyCode, "Verified Imports", "Alex Tan", address, PASSWORD)));
	}

	private ResultActions verify(String token) throws Exception {
		return mvc.perform(post("/api/auth/verify-email").contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"" + token + "\"}"));
	}

	private ResultActions resend(String address) throws Exception {
		return mvc.perform(post("/api/auth/resend-verification").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"" + address + "\"}"));
	}

	@TestConfiguration
	static class CapturingMail {

		@Bean
		@Primary
		CapturingEmailVerificationMailer emailVerificationMailer() {
			return new CapturingEmailVerificationMailer();
		}
	}
}
