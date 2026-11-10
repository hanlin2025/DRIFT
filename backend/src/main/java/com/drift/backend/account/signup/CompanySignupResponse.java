package com.drift.backend.account.signup;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CompanySignupResponse", description = "The same body for every accepted signup, so it does not reveal whether the email was already registered.")
public record CompanySignupResponse(
		@Schema(example = "Check your email to verify your account before signing in.") String message) {

	public static final String ACCEPTED = "Check your email to verify your account before signing in.";

	public static CompanySignupResponse accepted() {
		return new CompanySignupResponse(ACCEPTED);
	}
}
