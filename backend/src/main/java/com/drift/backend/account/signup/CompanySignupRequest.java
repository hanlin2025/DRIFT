package com.drift.backend.account.signup;

import java.util.Locale;

import com.drift.backend.organisation.exception.InvalidOrganisationException;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(name = "CompanySignupRequest", description = "A new company and the details of its first administrator.")
public record CompanySignupRequest(
		@Schema(example = "PACIFIC_GATE", description = "Letters, digits and underscores, starting with a letter; at most 50 characters. Stored in upper case.")
		@NotBlank(message = InvalidOrganisationException.CODE) String companyCode,
		@Schema(example = "Pacific Gate Imports", description = "1 to 200 characters with no control characters.")
		@NotBlank(message = InvalidOrganisationException.NAME) String companyName,
		@Schema(example = "Alex Tan")
		@NotBlank(message = "Full name is required")
		@Size(max = 200, message = "Full name must be at most 200 characters") String fullName,
		@Schema(example = "alex@pacificgate.example")
		@NotBlank(message = "Email is required")
		@Email(message = "Email must be a valid email address")
		@Size(max = 320, message = "Email must be at most 320 characters") String email,
		@Schema(example = "Str0ngPass", description = "At least 8 characters with an upper-case letter, a lower-case letter and a digit, no spaces, at most 72 UTF-8 bytes.")
		@NotBlank(message = "Password is required")
		@Size(max = 72, message = "Password must be at most 72 UTF-8 bytes") String password) {

	public CompanySignupRequest {
		fullName = fullName == null ? null : fullName.strip();
		email = email == null ? null : email.strip().toLowerCase(Locale.ROOT);
	}

	@Override
	public String toString() {
		return "CompanySignupRequest[redacted]";
	}
}
