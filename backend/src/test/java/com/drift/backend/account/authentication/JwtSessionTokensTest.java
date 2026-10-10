package com.drift.backend.account.authentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.exception.SessionEndedException;

class JwtSessionTokensTest {

	private static final String SECRET = "test-only-jwt-secret-not-used-outside-tests";

	@Test
	void issuesATokenThatCarriesTheAccountAndExpiry() throws Exception {
		Instant now = Instant.parse("2026-09-25T00:00:00Z");
		JwtSessionTokens tokens = tokensAt(now);
		IssuedSession session = tokens.issue(account());

		AuthenticatedUser user = tokens.parse(session.token());

		assertThat(user.id()).isEqualTo(15L);
		assertThat(user.email()).isEqualTo("alice@example.com");
		assertThat(user.role()).isEqualTo(Role.FREIGHT_FORWARDER);
		assertThat(session.expiresAt()).isEqualTo(now.plus(Duration.ofHours(1)));
		assertThat(user.expiresAt()).isEqualTo(session.expiresAt());
	}

	@Test
	void rejectsExpiredAndTamperedTokens() throws Exception {
		Instant now = Instant.parse("2026-09-25T00:00:00Z");
		IssuedSession session = tokensAt(now).issue(account());

		assertThatThrownBy(() -> tokensAt(now.plus(Duration.ofHours(2))).parse(session.token()))
				.isInstanceOf(SessionEndedException.class);
		assertThatThrownBy(() -> tokensAt(now).parse(session.token() + "x"))
				.isInstanceOf(SessionEndedException.class);
		assertThatThrownBy(() -> tokensAt(now).parse("not-a-token"))
				.isInstanceOf(SessionEndedException.class);
	}

	@Test
	void rejectsASecretThatIsTooShort() {
		assertThatThrownBy(() -> new JwtSessionTokens("too-short", 3_600_000, Clock.systemUTC()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32 bytes");
	}

	private static JwtSessionTokens tokensAt(Instant now) {
		return new JwtSessionTokens(SECRET, Duration.ofHours(1).toMillis(), Clock.fixed(now, ZoneOffset.UTC));
	}

	private static UserAccount account() throws Exception {
		UserAccount account = new UserAccount("Alice Tan", "alice@example.com", "hash",
				new AccountRole(Role.FREIGHT_FORWARDER), null);
		Field id = UserAccount.class.getDeclaredField("id");
		id.setAccessible(true);
		id.set(account, 15L);
		return account;
	}
}
