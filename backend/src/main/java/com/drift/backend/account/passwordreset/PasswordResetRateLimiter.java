package com.drift.backend.account.passwordreset;

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

@Component
public class PasswordResetRateLimiter {

	private final int emailLimit;
	private final int addressLimit;
	private final Duration window;
	private final Clock clock;
	private final Object lock = new Object();
	private final Map<String, ArrayDeque<Instant>> attempts = new HashMap<>();

	@Autowired
	public PasswordResetRateLimiter(
			@Value("${app.password-reset.rate-limit.email}") int emailLimit,
			@Value("${app.password-reset.rate-limit.ip}") int addressLimit,
			@Value("${app.password-reset.rate-limit.window}") Duration window,
			ObjectProvider<Clock> clocks) {
		this(emailLimit, addressLimit, window, clocks.getIfAvailable(Clock::systemUTC));
	}

	public PasswordResetRateLimiter(int emailLimit, int addressLimit, Duration window, Clock clock) {
		if (emailLimit < 1 || addressLimit < 1 || window == null || window.isZero() || window.isNegative()) {
			throw new IllegalStateException("app.password-reset.rate-limit must be positive");
		}
		if (clock == null) {
			throw new IllegalStateException("A clock is required");
		}
		this.emailLimit = emailLimit;
		this.addressLimit = addressLimit;
		this.window = window;
		this.clock = clock;
	}

	public void allowForgotPassword(String email, String clientAddress) {
		synchronized (lock) {
			Instant now = clock.instant();
			String emailKey = "email:" + email;
			String addressKey = addressKey(clientAddress);
			if (count(emailKey, now) >= emailLimit || count(addressKey, now) >= addressLimit) {
				throw new PasswordResetRateLimitException();
			}
			add(emailKey, now);
			add(addressKey, now);
		}
	}

	public void allowReset(String clientAddress) {
		synchronized (lock) {
			Instant now = clock.instant();
			String addressKey = addressKey(clientAddress);
			if (count(addressKey, now) >= addressLimit) {
				throw new PasswordResetRateLimitException();
			}
			add(addressKey, now);
		}
	}

	private static String addressKey(String clientAddress) {
		if (clientAddress == null || clientAddress.isBlank()) {
			return "ip:unknown";
		}
		return "ip:" + clientAddress;
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
