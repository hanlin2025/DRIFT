package com.drift.backend.account.signup;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "VerificationMessage")
public record VerificationMessageResponse(String message) {

	public static final String VERIFIED = "Your email has been verified. You can now sign in.";
	public static final String RESENT = "If this email is awaiting verification, a new link has been sent.";

	static VerificationMessageResponse verified() {
		return new VerificationMessageResponse(VERIFIED);
	}

	static VerificationMessageResponse resent() {
		return new VerificationMessageResponse(RESENT);
	}
}
