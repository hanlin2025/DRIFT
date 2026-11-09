package com.drift.backend.account.setup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.setup.token=test-only-setup-token-not-used-outside-tests")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SetupConcurrencyIntegrationTests {

	private static final String CREDENTIAL = "test-only-setup-token-not-used-outside-tests";

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	private String firstEmail;
	private String secondEmail;

	@BeforeEach
	void emails() {
		firstEmail = "cdg309-race-" + UUID.randomUUID() + "@example.com";
		secondEmail = "cdg309-race-" + UUID.randomUUID() + "@example.com";
	}

	@AfterEach
	void removeCreatedAccounts() {
		jdbc.update("DELETE FROM installation_setup");
		jdbc.update("DELETE FROM users WHERE email LIKE 'cdg309-race-%'");
	}

	@Test
	void concurrentSetupAttemptsCreateOnlyOneAdministrator() throws Exception {
		Long harbourline = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'HARBOURLINE_DEMO'", Long.class);
		Long straits = jdbc.queryForObject("SELECT id FROM companies WHERE code = 'STRAITS_FRESH_DEMO'", Long.class);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger created = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = pool.submit(() -> attempt(ready, start, harbourline, firstEmail, created, rejected));
			Future<?> second = pool.submit(() -> attempt(ready, start, straits, secondEmail, created, rejected));
			ready.await();
			start.countDown();
			first.get();
			second.get();
		} finally {
			pool.shutdownNow();
		}

		assertThat(created).hasValue(1);
		assertThat(rejected).hasValue(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM installation_setup", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM users AS account
				JOIN roles AS role ON role.id = account.role_id
				WHERE role.code = 'ADMIN' AND account.email LIKE 'cdg309-race-%'
				""", Integer.class)).isEqualTo(1);
	}

	private void attempt(CountDownLatch ready, CountDownLatch start, Long organisationId, String email,
			AtomicInteger created, AtomicInteger rejected) {
		ready.countDown();
		try {
			start.await();
			int status = mvc.perform(post("/api/setup/administrator")
					.header(SetupController.CREDENTIAL_HEADER, CREDENTIAL)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"organisationId":%d,"fullName":"Ada Admin","email":"%s","password":"Example123"}
							""".formatted(organisationId, email)))
					.andReturn().getResponse().getStatus();
			if (status == 201) {
				created.incrementAndGet();
			} else if (status == 409) {
				rejected.incrementAndGet();
			} else {
				throw new IllegalStateException("Unexpected setup status " + status);
			}
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}
}
