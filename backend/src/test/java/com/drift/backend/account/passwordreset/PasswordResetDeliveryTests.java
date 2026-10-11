package com.drift.backend.account.passwordreset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PasswordResetDeliveryTests.DeliveryDoubles.class)
class PasswordResetDeliveryTests {

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired HoldingMailer mailer;
	@MockitoSpyBean PasswordResetStore store;

	private String email;

	@BeforeEach
	void account() {
		mailer.reset();
		reset(store);
		email = "cdg114-delivery-" + UUID.randomUUID() + "@example.com";
		jdbc.update("""
				INSERT INTO users (full_name, email, password_hash, role_id)
				SELECT 'Alex', ?, 'hash', id
				FROM roles WHERE code = 'FREIGHT_FORWARDER'
				""", email);
	}

	@AfterEach
	void removeAccount() {
		jdbc.update("""
				DELETE FROM password_reset_tokens
				WHERE user_id IN (SELECT id FROM users WHERE email = ?)
				""", email);
		jdbc.update("DELETE FROM users WHERE email = ?", email);
	}

	@Test
	void anOverlappingRequestKeepsTheLaterEmailedLink() throws Exception {
		CountDownLatch arrived = new CountDownLatch(2);
		CountDownLatch published = new CountDownLatch(1);
		AtomicBoolean snapshotted = new AtomicBoolean();
		List<Map<String, Object>> duringSend = new ArrayList<>();
		mailer.duringSend = () -> {
			arrived.countDown();
			try {
				if (!arrived.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("the other reset request did not reach send");
				}
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
			if (snapshotted.compareAndSet(false, true)) {
				try {
					duringSend.addAll(unusedTokens());
				} finally {
					published.countDown();
				}
			} else {
				try {
					if (!published.await(10, TimeUnit.SECONDS)) {
						throw new IllegalStateException("the unused-token snapshot was not published");
					}
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(ex);
				}
			}
		};

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = pool.submit(() -> send(email));
			Future<?> second = pool.submit(() -> send(email));
			first.get(20, TimeUnit.SECONDS);
			second.get(20, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		assertThat(duringSend).hasSize(2);
		assertThat(mailer.links).hasSize(2);
		long laterId = duringSend.stream().mapToLong(row -> ((Number) row.get("id")).longValue()).max().orElseThrow();
		String laterHash = duringSend.stream()
				.filter(row -> ((Number) row.get("id")).longValue() == laterId)
				.map(row -> (String) row.get("token_hash"))
				.findFirst().orElseThrow();
		assertThat(unusedTokens()).singleElement().extracting(row -> row.get("token_hash")).isEqualTo(laterHash);
	}

	@Test
	void aCleanupFailureKeepsTheEmailedLink() throws Exception {
		request(email);
		String first = tokenFrom(mailer.links.get(0));
		Logger logger = (Logger) LoggerFactory.getLogger(PasswordResetService.class);
		ListAppender<ILoggingEvent> logs = new ListAppender<>();
		logs.start();
		logger.addAppender(logs);
		doThrow(new IllegalStateException("cleanup failed")).when(store).deleteOlderUnusedLinks(anyLong(), anyLong());
		try {
			request(email);
		} finally {
			logger.detachAppender(logs);
		}

		String emailed = tokenFrom(mailer.links.get(1));
		assertThat(mailer.links).hasSize(2);
		assertThat(unusedHashes()).containsExactlyInAnyOrder(ResetTokens.hash(first), ResetTokens.hash(emailed));
		assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("could not be removed"));
		assertThat(logs.list).noneMatch(event -> event.getFormattedMessage().contains("could not be sent"));
	}

	private void send(String accountEmail) {
		try {
			request(accountEmail);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void request(String accountEmail) throws Exception {
		mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"%s\"}".formatted(accountEmail)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value(ForgotPasswordResponse.MESSAGE));
	}

	private List<Map<String, Object>> unusedTokens() {
		return jdbc.queryForList("""
				SELECT t.id, t.token_hash FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ? AND t.used_at IS NULL
				""", email);
	}

	private List<String> unusedHashes() {
		return jdbc.queryForList("""
				SELECT t.token_hash FROM password_reset_tokens t
				JOIN users u ON u.id = t.user_id
				WHERE u.email = ? AND t.used_at IS NULL
				""", String.class, email);
	}

	private static String tokenFrom(String link) {
		String marker = "#token=";
		int start = link.indexOf(marker);
		assertThat(start).isGreaterThan(0);
		return link.substring(start + marker.length());
	}

	@TestConfiguration
	static class DeliveryDoubles {

		@Bean
		@Primary
		HoldingMailer passwordResetMailer() {
			return new HoldingMailer();
		}
	}

	static final class HoldingMailer implements PasswordResetMailer {

		private final List<String> links = new ArrayList<>();
		private Runnable duringSend = () -> { };

		@Override
		public void sendResetLink(String email, String resetLink, Duration validFor) {
			duringSend.run();
			synchronized (links) {
				links.add(resetLink);
			}
		}

		void reset() {
			links.clear();
			duringSend = () -> { };
		}
	}
}
