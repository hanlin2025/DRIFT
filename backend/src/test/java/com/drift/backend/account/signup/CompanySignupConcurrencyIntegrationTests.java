package com.drift.backend.account.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.drift.backend.account.UserAccount;
import com.drift.backend.organisation.exception.DuplicateOrganisationException;

@SpringBootTest
@ActiveProfiles("test")
class CompanySignupConcurrencyIntegrationTests {

	private static final String PASSWORD = "Str0ngPass";

	@Autowired CompanySignupService signups;
	@Autowired JdbcTemplate jdbc;
	@MockitoSpyBean EmailVerificationTokens verificationTokens;

	private final List<String> emails = new ArrayList<>();
	private final List<String> codes = new ArrayList<>();

	@BeforeEach
	void resetSpy() {
		reset(verificationTokens);
	}

	@AfterEach
	void cleanup() {
		reset(verificationTokens);
		SignupFixtures.cleanup(jdbc, emails, codes);
	}

	@Test
	void aFailureAfterTheCompanyInsertLeavesNoCompanyUserOrToken() {
		String code = code();
		String email = email();
		doThrow(new IllegalStateException("simulated failure")).when(verificationTokens).issue(any(UserAccount.class));

		assertThatThrownBy(() -> signups.register(request(code, email), "203.0.113.1"))
				.isInstanceOf(IllegalStateException.class).hasMessage("simulated failure");

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE code = ?", Long.class, code)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM email_verification_tokens tokens JOIN users ON users.id = tokens.user_id WHERE users.email = ?
				""", Long.class, email)).isZero();
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM company_signup_audits audits JOIN companies ON companies.id = audits.company_id WHERE companies.code = ?
				""", Long.class, code)).isZero();

		reset(verificationTokens);
		assertThat(signups.register(request(code, email), "203.0.113.1").message()).isEqualTo(CompanySignupResponse.ACCEPTED);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE code = ?", Long.class, code)).isEqualTo(1L);
	}

	@Test
	void concurrentSignupsWithTheSameCodeCreateOneCompany() throws Exception {
		String code = code();
		String first = email();
		String second = email();
		AtomicInteger accepted = new AtomicInteger();
		AtomicInteger conflicts = new AtomicInteger();

		race(() -> attempt(request(code, first), accepted, conflicts), () -> attempt(request(code, second), accepted, conflicts));

		assertThat(accepted.get()).isEqualTo(1);
		assertThat(conflicts.get()).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE code = ?", Long.class, code)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email IN (?, ?)", Long.class, first, second)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM users WHERE company_id = (SELECT id FROM companies WHERE code = ?)
				""", Long.class, code)).isEqualTo(1L);
	}

	@Test
	void concurrentSignupsWithTheSameEmailCreateOneUserAndRollBackTheOtherCompany() throws Exception {
		String email = email();
		String first = code();
		String second = code();
		AtomicInteger accepted = new AtomicInteger();
		AtomicInteger conflicts = new AtomicInteger();

		race(() -> attempt(request(first, email), accepted, conflicts), () -> attempt(request(second, email), accepted, conflicts));

		assertThat(accepted.get()).isEqualTo(2);
		assertThat(conflicts.get()).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Long.class, email)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM companies WHERE code IN (?, ?)", Long.class, first, second))
				.isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = (SELECT id FROM users WHERE email = ?)
				""", Long.class, email)).isEqualTo(1L);
	}

	private void attempt(CompanySignupRequest request, AtomicInteger accepted, AtomicInteger conflicts) {
		try {
			assertThat(signups.register(request, "203.0.113.2").message()).isEqualTo(CompanySignupResponse.ACCEPTED);
			accepted.incrementAndGet();
		} catch (DuplicateOrganisationException ex) {
			conflicts.incrementAndGet();
		}
	}

	private static void race(Runnable first, Runnable second) throws Exception {
		ExecutorService workers = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			Future<?> a = workers.submit(() -> { await(start); first.run(); });
			Future<?> b = workers.submit(() -> { await(start); second.run(); });
			start.countDown();
			a.get(30, TimeUnit.SECONDS);
			b.get(30, TimeUnit.SECONDS);
		} finally {
			workers.shutdownNow();
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private static CompanySignupRequest request(String code, String email) {
		return new CompanySignupRequest(code, "Race Imports", "Race Admin", email, PASSWORD);
	}

	private String code() {
		String code = SignupFixtures.uniqueCode("CDG361R_");
		codes.add(code);
		return code;
	}

	private String email() {
		String email = SignupFixtures.uniqueEmail("cdg361-race");
		emails.add(email);
		return email;
	}
}
