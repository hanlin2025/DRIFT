package com.drift.backend.account.passwordreset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class PasswordResetRateLimiterTest {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-07T04:00:00Z"));
	private final PasswordResetRateLimiter limiter = new PasswordResetRateLimiter(2, 3, Duration.ofMinutes(15), clock);

	@Test
	void blocksOneEmailUntilItsWindowPassesWithoutBlockingAnother() {
		limiter.allowForgotPassword("a@example.com", "203.0.113.1");
		limiter.allowForgotPassword("a@example.com", "203.0.113.1");
		assertThatThrownBy(() -> limiter.allowForgotPassword("a@example.com", "203.0.113.2"))
				.isInstanceOf(PasswordResetRateLimitException.class)
				.hasMessage(PasswordResetRateLimitException.MESSAGE);
		limiter.allowForgotPassword("b@example.com", "203.0.113.2");

		clock.advance(Duration.ofMinutes(15));
		limiter.allowForgotPassword("a@example.com", "203.0.113.2");
	}

	@Test
	void countsForgotAndResetAttemptsAgainstTheSameAddress() {
		limiter.allowForgotPassword("a@example.com", "203.0.113.9");
		limiter.allowReset("203.0.113.9");
		limiter.allowReset("203.0.113.9");
		assertThatThrownBy(() -> limiter.allowForgotPassword("c@example.com", "203.0.113.9"))
				.isInstanceOf(PasswordResetRateLimitException.class);
		limiter.allowReset("203.0.113.10");
	}

	@Test
	void aMissingAddressSharesOneBucket() {
		PasswordResetRateLimiter tight = new PasswordResetRateLimiter(5, 1, Duration.ofMinutes(15), clock);
		tight.allowForgotPassword("a@example.com", " ");
		assertThatThrownBy(() -> tight.allowReset(null)).isInstanceOf(PasswordResetRateLimitException.class);
	}

	@Test
	void rejectsANonPositiveLimit() {
		assertThatThrownBy(() -> new PasswordResetRateLimiter(0, 1, Duration.ofMinutes(1), clock))
				.isInstanceOf(IllegalStateException.class);
	}

	private static final class MutableClock extends Clock {

		private Instant now;

		private MutableClock(Instant now) {
			this.now = now;
		}

		private void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}
}
