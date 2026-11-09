package com.drift.backend.account.authentication;

import java.time.Duration;
import java.time.Instant;

import org.springframework.http.ResponseCookie;

public final class SessionCookie {

	public static final String NAME = "DRIFT_SESSION";

	private SessionCookie() {
	}

	public static String set(String token, Instant expiresAt, Instant now, boolean secure) {
		long seconds = Math.max(0, Duration.between(now, expiresAt).getSeconds());
		return ResponseCookie.from(NAME, token)
				.httpOnly(true)
				.secure(secure)
				.sameSite("Lax")
				.path("/")
				.maxAge(Duration.ofSeconds(seconds))
				.build()
				.toString();
	}

	public static String clear(boolean secure) {
		return ResponseCookie.from(NAME, "")
				.httpOnly(true)
				.secure(secure)
				.sameSite("Lax")
				.path("/")
				.maxAge(0)
				.build()
				.toString();
	}
}
