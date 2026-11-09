package com.drift.backend.account.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Not {@code @Transactional}: each request must run in its own transaction on its own thread so the
 * row lock in {@link PasswordResetTokenRepository#findForReset} is what decides the winner.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PasswordResetIntegrationTests.CapturingMail.class)
class PasswordResetConcurrencyIntegrationTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired PasswordEncoder passwords;
	@Autowired PlatformTransactionManager transactions;

	private String email;

	@BeforeEach
	void account() {
		email = "cdg312-race-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id)
				SELECT 'Alex', ?, 'hash', id
				FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", email);
	}

	@AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM password_reset_tokens WHERE user_id IN (SELECT id FROM users WHERE email = ?)", email);
		jdbc.update("DELETE FROM users WHERE email = ?", email);
	}

	@Test
	void twoConcurrentResetsWithOneLinkSucceedExactlyOnce() throws Exception {
		String token = ResetTokens.generate();
		jdbc.update("""
				INSERT INTO password_reset_tokens (user_id, token_hash, expires_at)
				SELECT id, ?, now() + interval '1 hour' FROM users WHERE email = ?
				""", ResetTokens.hash(token), email);
		long generation = sessionGeneration();

		ExecutorService pool = Executors.newFixedThreadPool(3);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try {
			// A third transaction holds the token row so both resets are provably waiting on the same lock.
			Future<?> holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
				jdbc.queryForObject("SELECT id FROM password_reset_tokens WHERE token_hash = ? FOR UPDATE",
						Long.class, ResetTokens.hash(token));
				held.countDown();
				await(release);
			}));
			assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();

			List<Future<MockHttpServletResponse>> resets = new ArrayList<>();
			for (String password : List.of("FirstPass1", "SecondPass2")) {
				resets.add(pool.submit(() -> mvc.perform(post("/api/auth/reset-password")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"%s\",\"password\":\"%s\"}".formatted(token, password)))
						.andReturn().getResponse()));
			}
			waitUntilBlocked(2);
			release.countDown();
			holder.get(10, TimeUnit.SECONDS);

			List<MockHttpServletResponse> responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> reset : resets) {
				responses.add(reset.get(30, TimeUnit.SECONDS));
			}
			assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 400);
			MockHttpServletResponse loser = responses.stream().filter(r -> r.getStatus() == 400).findFirst().orElseThrow();
			assertThat(loser.getContentAsString()).contains(InvalidResetTokenException.MESSAGE);

			int winner = responses.get(0).getStatus() == 200 ? 0 : 1;
			String hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE email = ?", String.class, email);
			assertThat(passwords.matches(winner == 0 ? "FirstPass1" : "SecondPass2", hash)).isTrue();
			assertThat(sessionGeneration()).isEqualTo(generation + 1);
			assertThat(jdbc.queryForObject("""
					SELECT COUNT(*) FROM password_reset_tokens t
					JOIN users u ON u.id = t.user_id
					WHERE u.email = ? AND t.used_at IS NOT NULL
					""", Integer.class, email)).isEqualTo(1);
		} finally {
			release.countDown();
			pool.shutdownNow();
			pool.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	private void waitUntilBlocked(int expected) throws InterruptedException {
		Instant deadline = Instant.now().plus(Duration.ofSeconds(15));
		while (Instant.now().isBefore(deadline)) {
			Integer waiting = jdbc.queryForObject("""
					SELECT COUNT(*) FROM pg_stat_activity
					WHERE datname = current_database() AND wait_event_type = 'Lock'
					AND query ILIKE '%password_reset_tokens%'
					""", Integer.class);
			if (waiting != null && waiting >= expected) {
				return;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("Both resets should be waiting on the token row lock");
	}

	private long sessionGeneration() {
		return jdbc.queryForObject("SELECT session_generation FROM users WHERE email = ?", Long.class, email);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(30, TimeUnit.SECONDS)) {
				throw new IllegalStateException("The lock holder was never released");
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}
}
