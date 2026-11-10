package com.drift.backend.account.signup;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * In-memory sliding-window limits for company signup, email verification and verification resends, modelled on
 * the password reset limiter. Each action has its own buckets, so signing up does not use up the verify allowance.
 * Attempts count whether or not the email is registered, so a 429 reveals nothing about an account.
 */
@Component
public class SignupRateLimiter {

	private final int addressLimit;
	private final int emailLimit;
	private final Duration window;
	private final Clock clock;
	private final Object lock = new Object();
	private final Map<String, ArrayDeque<Instant>> attempts = new HashMap<>();

	@Autowired
	public SignupRateLimiter(
			@Value("${app.signup.rate-limit.ip}") int addressLimit,
			@Value("${app.signup.rate-limit.email}") int emailLimit,
			@Value("${app.signup.rate-limit.window}") Duration window,
			ObjectProvider<Clock> clocks) {
		this(addressLimit, emailLimit, window, clocks.getIfAvailable(Clock::systemUTC));
	}

	public SignupRateLimiter(int addressLimit, int emailLimit, Duration window, Clock clock) {
		if (emailLimit < 1 || addressLimit < 1 || window == null || window.isZero() || window.isNegative()) {
			throw new IllegalStateException("app.signup.rate-limit must be positive");
		}
		if (clock == null) {
			throw new IllegalStateException("A clock is required");
		}
		this.addressLimit = addressLimit;
		this.emailLimit = emailLimit;
		this.window = window;
		this.clock = clock;
	}

	public void allowSignup(String email, String clientAddress) {
		allow("signup", email, clientAddress);
	}

	public void allowVerification(String clientAddress) {
		allow("verify", null, clientAddress);
	}

	public void allowResend(String email, String clientAddress) {
		allow("resend", email, clientAddress);
	}

	private void allow(String action, String email, String clientAddress) {
		synchronized (lock) {
			Instant now = clock.instant();
			String addressKey = action + ":ip:" + (clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress);
			String emailKey = email == null ? null : action + ":email:" + email;
			if (count(addressKey, now) >= addressLimit || (emailKey != null && count(emailKey, now) >= emailLimit)) {
				throw new SignupRateLimitException();
			}
			add(addressKey, now);
			if (emailKey != null) {
				add(emailKey, now);
			}
		}
	}

	private int count(String key, Instant now) {
		ArrayDeque<Instant> hits = attempts.get(key);
		if (hits == null) {
			return 0;
		}
		Instant oldest = now.minus(window);
		while (!hits.isEmpty() && !hits.peekFirst().isAfter(oldest)) {
			hits.removeFirst();
		}
		if (hits.isEmpty()) {
			attempts.remove(key);
			return 0;
		}
		return hits.size();
	}

	private void add(String key, Instant now) {
		attempts.computeIfAbsent(key, ignored -> new ArrayDeque<>()).addLast(now);
	}
}
