package com.drift.backend.account.signup;

import org.springframework.stereotype.Service;

@Service
public class EmailVerificationService {

	private final EmailVerificationStore store;
	private final SignupRateLimiter rateLimiter;

	public EmailVerificationService(EmailVerificationStore store, SignupRateLimiter rateLimiter) {
		this.store = store;
		this.rateLimiter = rateLimiter;
	}

	public VerificationMessageResponse verify(VerifyEmailRequest request, String clientAddress) {
		rateLimiter.allowVerification(clientAddress);
		if (!store.verify(request.token())) {
			throw new InvalidVerificationTokenException();
		}
		return VerificationMessageResponse.verified();
	}

	public VerificationMessageResponse resend(ResendVerificationRequest request, String clientAddress) {
		rateLimiter.allowResend(request.email(), clientAddress);
		store.reissue(request.email());
		return VerificationMessageResponse.resent();
	}
}
