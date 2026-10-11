package com.drift.backend.account.authentication;

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.exception.SessionEndedException;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

@Component
public class JwtSessionTokens {

	private final byte[] secret;
	private final Duration ttl;
	private final Clock clock;

	@Autowired
	public JwtSessionTokens(
			@Value("${app.jwt.secret}") String secret,
			@Value("${app.jwt.expiration-ms}") long expirationMs,
			ObjectProvider<Clock> clocks) {
		this(secret, expirationMs, clocks.getIfAvailable(Clock::systemUTC));
	}

	public JwtSessionTokens(String secret, long expirationMs, Clock clock) {
		if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
			throw new IllegalStateException("app.jwt.secret must be at least 32 bytes");
		}
		if (expirationMs <= 0) {
			throw new IllegalStateException("app.jwt.expiration-ms must be positive");
		}
		this.secret = secret.getBytes(StandardCharsets.UTF_8);
		this.ttl = Duration.ofMillis(expirationMs);
		this.clock = clock;
	}

	public IssuedSession issue(UserAccount account) {
		if (account.getId() == null) {
			throw new IllegalStateException("A session can only be issued for a saved account");
		}
		Instant now = clock.instant();
		Instant expiresAt = now.plus(ttl);
		JWTClaimsSet claims = new JWTClaimsSet.Builder()
				.subject(account.getId().toString())
				.claim("email", account.getEmail())
				.claim("role", account.getRole().name())
				.claim("gen", account.getSessionGeneration())
				.issueTime(Date.from(now))
				.expirationTime(Date.from(expiresAt))
				.build();
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
			jwt.sign(new MACSigner(secret));
			return new IssuedSession(jwt.serialize(), expiresAt);
		} catch (JOSEException ex) {
			throw new IllegalStateException("Could not issue a session", ex);
		}
	}

	public AuthenticatedUser parse(String token) {
		if (token == null || token.isBlank()) {
			throw new SessionEndedException();
		}
		try {
			SignedJWT jwt = SignedJWT.parse(token);
			if (!jwt.verify(new MACVerifier(secret))) {
				throw new SessionEndedException();
			}
			JWTClaimsSet claims = jwt.getJWTClaimsSet();
			Date expiration = claims.getExpirationTime();
			if (expiration == null || !expiration.toInstant().isAfter(clock.instant())) {
				throw new SessionEndedException();
			}
			Long id = Long.valueOf(claims.getSubject());
			String email = claims.getStringClaim("email");
			Role role = Role.valueOf(claims.getStringClaim("role"));
			if (email == null || email.isBlank()) {
				throw new SessionEndedException();
			}
			Long generation = claims.getLongClaim("gen");
			return new AuthenticatedUser(id, email, role, expiration.toInstant(), generation == null ? 0 : generation);
		} catch (SessionEndedException ex) {
			throw ex;
		} catch (ParseException | JOSEException | RuntimeException ex) {
			throw new SessionEndedException();
		}
	}
}
