package com.drift.backend.account.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class SignupRateLimiterTest {

	private static final Duration WINDOW = Duration.ofMinutes(15);

	@Test
	void prunesKeysWhoseNewestAttemptIsOlderThanTheWindow() {
		FakeClock clock = new FakeClock();
		SignupRateLimiter limiter = new SignupRateLimiter(1000, 1000, WINDOW, clock);
		for (int i = 0; i < 50; i++) {
			limiter.allowSignup("user" + i + "@example.com", "203.0.113.1");
		}
		// 50 email buckets plus one address bucket.
		assertThat(limiter.trackedKeys()).isEqualTo(51);

		clock.advance(WINDOW.plusSeconds(1));
		for (int i = 0; i < SignupRateLimiter.PRUNE_EVERY - 51; i++) {
			limiter.allowVerification("203.0.113.9");
		}
		assertThat(limiter.trackedKeys()).isEqualTo(52);

		// The PRUNE_EVERY-th check sweeps the expired signup buckets before recording itself.
		limiter.allowVerification("203.0.113.9");
		assertThat(limiter.trackedKeys()).isEqualTo(1);
	}

	@Test
	void keepsKeysThatAreStillInsideTheWindow() {
		FakeClock clock = new FakeClock();
		SignupRateLimiter limiter = new SignupRateLimiter(1000, 2, WINDOW, clock);
		limiter.allowResend("kept@example.com", "203.0.113.1");
		limiter.allowResend("kept@example.com", "203.0.113.2");
		clock.advance(WINDOW.minusMinutes(1));
		for (int i = 0; i < SignupRateLimiter.PRUNE_EVERY; i++) {
			limiter.allowVerification("203.0.113.9");
		}
		assertThatThrownBy(() -> limiter.allowResend("kept@example.com", "203.0.113.3"))
				.isInstanceOf(SignupRateLimitException.class);
		clock.advance(Duration.ofMinutes(2));
		limiter.allowResend("kept@example.com", "203.0.113.3");
	}

	private static final class FakeClock extends Clock {

		private Instant now = Instant.parse("2026-11-01T00:00:00Z");

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
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
}
