package com.drift.backend.account.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
		"app.password-reset.rate-limit.email=3",
		"app.password-reset.rate-limit.ip=5",
		"app.password-reset.rate-limit.window=15m"
})
@Import(PasswordResetSecurityTests.CapturingMail.class)
class PasswordResetSecurityTests {

	private static final String FAKE_TOKEN = "b".repeat(43);

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired CapturingPasswordResetMailer mailer;

	private String email;

	@BeforeEach
	void account() {
		mailer.reset();
		email = "cdg117-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role)
				VALUES ('Alex', ?, 'hash', 'FREIGHT_FORWARDER')
				""", email);
	}

	@Test
	void repeatedEmailsAreLimitedWithTheSameResponseWhetherOrNotTheyExist() throws Exception {
		String address = "203.0.113.10";
		for (int attempt = 0; attempt < 3; attempt++) {
			forgot(email, address).andExpect(status().isOk())
					.andExpect(jsonPath("$.message").value(ForgotPasswordResponse.MESSAGE));
		}
		MvcResult known = forgot(email, address)
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.message").value(PasswordResetRateLimitException.MESSAGE))
				.andExpect(jsonPath("$.token").doesNotExist())
				.andReturn();
		assertThat(mailer.links).hasSize(3);
		assertThat(countTokens(email)).isEqualTo(1);

		forgot("other-" + email, address).andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(ForgotPasswordResponse.MESSAGE));

		String missing = "missing-" + email;
		String otherAddress = "203.0.113.11";
		for (int attempt = 0; attempt < 3; attempt++) {
			forgot(missing, otherAddress).andExpect(status().isOk())
					.andExpect(jsonPath("$.message").value(ForgotPasswordResponse.MESSAGE));
		}
		MvcResult unknown = forgot(missing, otherAddress)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.message").value(PasswordResetRateLimitException.MESSAGE))
				.andReturn();
		assertThat(unknown.getResponse().getContentAsString()).isEqualTo(known.getResponse().getContentAsString());
		assertThat(unknown.getResponse().getContentAsString()).doesNotContain(missing);
		assertThat(mailer.links).hasSize(3);
		assertThat(countTokens(missing)).isZero();
	}

	@Test
	void oneAddressIsLimitedEvenWhenEveryEmailIsDifferent() throws Exception {
		String address = "203.0.113.20";
		for (int attempt = 0; attempt < 5; attempt++) {
			forgot("ip-" + attempt + "-" + email, address).andExpect(status().isOk())
					.andExpect(jsonPath("$.message").value(ForgotPasswordResponse.MESSAGE));
		}
		forgot("ip-blocked-" + email, address)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.message").value(PasswordResetRateLimitException.MESSAGE));
		forgot("ip-open-" + email, "203.0.113.21").andExpect(status().isOk());
		assertThat(mailer.links).isEmpty();
	}

	@Test
	void resetAttemptsFromOneAddressStopBeforeTheLinkIsConsumed() throws Exception {
		forgot(email, "203.0.113.30").andExpect(status().isOk());
		String token = tokenFrom(mailer.links.get(0));
		String address = "203.0.113.31";
		for (int attempt = 0; attempt < 5; attempt++) {
			reset(FAKE_TOKEN, "NewPass1", address)
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors.token").value(InvalidResetTokenException.MESSAGE));
		}
		reset(token, "NewPass1", address)
				.andExpect(status().isTooManyRequests())
				.andExpect(jsonPath("$.message").value(PasswordResetRateLimitException.MESSAGE));
		assertThat(jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email))
				.isEqualTo("hash");
		assertThat(jdbc.queryForObject("""
				SELECT t.used_at FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ?
				""", Object.class, email)).isNull();

		reset(token, "NewPass1", "203.0.113.32")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(ResetPasswordResponse.MESSAGE));
	}

	private ResultActions forgot(String accountEmail, String address)
			throws Exception {
		return mvc.perform(post("/api/auth/forgot-password")
				.with(request -> {
					request.setRemoteAddr(address);
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(accountEmail)));
	}

	private ResultActions reset(String token, String password, String address)
			throws Exception {
		return mvc.perform(post("/api/auth/reset-password")
				.with(request -> {
					request.setRemoteAddr(address);
					return request;
				})
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, password)));
	}

	private int countTokens(String accountEmail) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ?
				""", Integer.class, accountEmail);
	}

	private static String tokenFrom(String link) {
		String marker = "#token=";
		int start = link.indexOf(marker);
		assertThat(start).isGreaterThan(0);
		return link.substring(start + marker.length());
	}

	@TestConfiguration
	static class CapturingMail {

		@Bean
		@Primary
		CapturingPasswordResetMailer passwordResetMailer() {
			return new CapturingPasswordResetMailer();
		}
	}

	static final class CapturingPasswordResetMailer implements PasswordResetMailer {

		private final List<String> links = new ArrayList<>();

		@Override
		public void sendResetLink(String email, String resetLink, Duration validFor) {
			links.add(resetLink);
		}

		void reset() {
			links.clear();
		}
	}
}
