package com.drift.backend.account.signup;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.drift.backend.account.passwordreset.ResetTokens;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmailVerificationRateLimitIntegrationTests.Limits.class)
class EmailVerificationRateLimitIntegrationTests {

	private static final Duration WINDOW = Duration.ofMinutes(10);
	private static final String LIMITED = "{\"message\":\"Too many requests. Try again later.\",\"errors\":{}}";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	// Given only to the test limiter, not registered as a Clock bean that other components could pick up.
	private static final MutableClock clock = new MutableClock();

	@Autowired CapturingEmailVerificationMailer mailer;

	private final List<String> emails = new ArrayList<>();
	private final List<String> codes = new ArrayList<>();
	private int address;

	@BeforeEach
	void freshWindow() {
		// Moving past the window empties every bucket from earlier tests.
		clock.advance(WINDOW.multipliedBy(2));
		mailer.reset();
	}

	@AfterEach
	void cleanup() {
		SignupFixtures.cleanup(jdbc, emails, codes);
	}

	@Test
	void signupIsLimitedPerEmailWithTheSameBodyForRegisteredAndUnknownEmails() throws Exception {
		String registered = track(SignupFixtures.uniqueEmail("cdg363-rl-known"));
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id, company_id)
				SELECT 'Known', ?, ?, id, (SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO') FROM roles WHERE code = 'IMPORTER'
				""", registered, passwords.encode("Str0ngPass"));
		String unknown = track(SignupFixtures.uniqueEmail("cdg363-rl-unknown"));

		for (String email : List.of(registered, unknown)) {
			for (int attempt = 0; attempt < 2; attempt++) {
				signup(email, nextAddress()).andExpect(status().isAccepted());
			}
			signup(email, nextAddress())
					.andExpect(status().isTooManyRequests())
					.andExpect(header().string("Cache-Control", "no-store"))
					.andExpect(content().json(LIMITED, JsonCompareMode.STRICT));
		}

		clock.advance(WINDOW.plusSeconds(1));
		signup(unknown, nextAddress()).andExpect(status().isAccepted());
	}

	@Test
	void signupIsLimitedPerClientAddress() throws Exception {
		String ip = nextAddress();
		for (int attempt = 0; attempt < 3; attempt++) {
			signup(track(SignupFixtures.uniqueEmail("cdg363-rl-ip")), ip).andExpect(status().isAccepted());
		}
		signup(track(SignupFixtures.uniqueEmail("cdg363-rl-ip")), ip)
				.andExpect(status().isTooManyRequests())
				.andExpect(content().json(LIMITED, JsonCompareMode.STRICT));
	}

	@Test
	void verificationIsLimitedPerClientAddress() throws Exception {
		String ip = nextAddress();
		for (int attempt = 0; attempt < 3; attempt++) {
			perform(post("/api/auth/verify-email"), "{\"token\":\"" + ResetTokens.generate() + "\"}", ip)
					.andExpect(status().isBadRequest());
		}
		perform(post("/api/auth/verify-email"), "{\"token\":\"" + ResetTokens.generate() + "\"}", ip)
				.andExpect(status().isTooManyRequests())
				.andExpect(content().json(LIMITED, JsonCompareMode.STRICT));
		perform(post("/api/auth/verify-email"), "{\"token\":\"" + ResetTokens.generate() + "\"}", nextAddress())
				.andExpect(status().isBadRequest());
	}

	@Test
	void resendIsLimitedPerEmailAndPerAddressWithTheSameBodyForEveryEmail() throws Exception {
		String pending = track(SignupFixtures.uniqueEmail("cdg363-rl-pending"));
		signup(pending, nextAddress()).andExpect(status().isAccepted());
		String unknown = track(SignupFixtures.uniqueEmail("cdg363-rl-none"));

		for (String email : List.of(pending, unknown)) {
			for (int attempt = 0; attempt < 2; attempt++) {
				resend(email, nextAddress()).andExpect(status().isOk());
			}
			resend(email, nextAddress())
					.andExpect(status().isTooManyRequests())
					.andExpect(content().json(LIMITED, JsonCompareMode.STRICT));
		}

		String ip = nextAddress();
		for (int attempt = 0; attempt < 3; attempt++) {
			resend(track(SignupFixtures.uniqueEmail("cdg363-rl-addr")), ip).andExpect(status().isOk());
		}
		resend(track(SignupFixtures.uniqueEmail("cdg363-rl-addr")), ip)
				.andExpect(status().isTooManyRequests())
				.andExpect(content().json(LIMITED, JsonCompareMode.STRICT));
	}

	private ResultActions signup(String email, String ip) throws Exception {
		String code = SignupFixtures.uniqueCode("CDG363L_");
		codes.add(code);
		return perform(post("/api/register/company"),
				SignupFixtures.body(code, "Limited Imports", "Lee Tan", email, "Str0ngPass"), ip);
	}

	private ResultActions resend(String email, String ip) throws Exception {
		return perform(post("/api/auth/resend-verification"), "{\"email\":\"" + email + "\"}", ip);
	}

	private ResultActions perform(MockHttpServletRequestBuilder request, String body, String ip) throws Exception {
		return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body)
				.with(servlet -> {
					servlet.setRemoteAddr(ip);
					return servlet;
				}));
	}

	private String nextAddress() {
		return "192.0.2." + (++address);
	}

	private String track(String email) {
		emails.add(email);
		return email;
	}

	static final class MutableClock extends Clock {

		private Instant now = Instant.parse("2026-11-01T00:00:00Z");

		synchronized void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public synchronized Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	@TestConfiguration
	static class Limits {

		@Bean
		@Primary
		SignupRateLimiter testSignupRateLimiter() {
			return new SignupRateLimiter(3, 2, WINDOW, clock);
		}

		@Bean
		@Primary
		CapturingEmailVerificationMailer emailVerificationMailer() {
			return new CapturingEmailVerificationMailer();
		}
	}
}
