package com.drift.backend.account.passwordreset;

import java.time.Duration;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.drift.backend.account.registration.PasswordPolicy;

@Service
public class PasswordResetService {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

	private final PasswordResetStore store;
	private final PasswordEncoder passwordEncoder;
	private final PasswordResetMailer mailer;
	private final String linkBaseUrl;
	private final Duration ttl;

	public PasswordResetService(PasswordResetStore store, PasswordEncoder passwordEncoder, PasswordResetMailer mailer,
			@Value("${app.password-reset.link-base-url}") String linkBaseUrl,
			@Value("${app.password-reset.ttl}") Duration ttl) {
		this.store = store;
		this.passwordEncoder = passwordEncoder;
		this.mailer = mailer;
		this.linkBaseUrl = linkBaseUrl;
		this.ttl = ttl;
	}

	public ForgotPasswordResponse request(ForgotPasswordRequest request) {
		Optional<PasswordResetStore.IssuedReset> issued = store.issue(request.email());
		issued.ifPresent(token -> deliver(request.email(), token));
		return ForgotPasswordResponse.generic();
	}

	public ResetPasswordResponse reset(ResetPasswordRequest request) {
		PasswordPolicy.check(request.password());
		if (!store.complete(request.token(), passwordEncoder.encode(request.password()))) {
			throw new InvalidResetTokenException();
		}
		return ResetPasswordResponse.completed();
	}

	private void deliver(String email, PasswordResetStore.IssuedReset issued) {
		try {
			mailer.sendResetLink(email, ResetTokens.link(linkBaseUrl, issued.rawToken()), ttl);
		} catch (RuntimeException ex) {
			store.discard(issued.id());
			log.error("Password reset email could not be sent", ex);
			return;
		}
		try {
			store.deleteOlderUnusedLinks(issued.userId(), issued.id());
		} catch (RuntimeException ex) {
			log.error("Older password reset links could not be removed", ex);
		}
	}
}
