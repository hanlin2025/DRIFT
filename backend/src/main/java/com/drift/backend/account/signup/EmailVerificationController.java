package com.drift.backend.account.signup;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

@RestController
@Tag(name = "Email verification", description = "Verify the email of a new company administrator and request a new verification link.")
public class EmailVerificationController {

	private final EmailVerificationService verifications;

	public EmailVerificationController(EmailVerificationService verifications) {
		this.verifications = verifications;
	}

	@PostMapping("/api/auth/verify-email")
	@Operation(summary = "Verify an email address with a verification link",
			description = "Public. Takes the token from the #token= fragment of an emailed link of the form "
					+ "{link-base-url}#token=<43-character token>. On success the link is used up, the account's other unused "
					+ "links are deleted, the account becomes active and its company is activated. A used, expired, replaced or "
					+ "unknown link gets the same response. Attempts are limited per client address.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Email verified; the administrator can sign in",
					content = @Content(mediaType = "application/json", schema = @Schema(implementation = VerificationMessageResponse.class),
							examples = @ExampleObject(value = """
									{"message":"Your email has been verified. You can now sign in."}
									"""))),
			@ApiResponse(responseCode = "400", description = "A missing, malformed, used, expired, replaced or unknown link", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"This verification link is invalid or has expired. Please request a new one.","errors":{"token":"This verification link is invalid or has expired. Please request a new one."}}
							"""))),
			@ApiResponse(responseCode = "429", description = "Too many attempts from this address", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Too many requests. Try again later.","errors":{}}
							"""))) })
	public ResponseEntity<VerificationMessageResponse> verify(@Valid @RequestBody VerifyEmailRequest request,
			HttpServletRequest http) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(verifications.verify(request, CompanySignupController.clientAddress(http)));
	}

	@PostMapping("/api/auth/resend-verification")
	@Operation(summary = "Request a new verification link",
			description = "Public. If the email belongs to an account awaiting verification, earlier unused links stop working "
					+ "and a new link is emailed. Otherwise nothing happens. The response is the same in every case. "
					+ "Attempts are limited per email and per client address.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Request accepted; the same message for every email",
					content = @Content(mediaType = "application/json", schema = @Schema(implementation = VerificationMessageResponse.class),
							examples = @ExampleObject(value = """
									{"message":"If this email is awaiting verification, a new link has been sent."}
									"""))),
			@ApiResponse(responseCode = "400", description = "Missing or malformed email", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Verification information is missing or invalid","errors":{"email":"Email must be a valid email address"}}
							"""))),
			@ApiResponse(responseCode = "429", description = "Too many requests for this email or from this address; "
					+ "the same response whether or not the email is registered", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Too many requests. Try again later.","errors":{}}
							"""))) })
	public ResponseEntity<VerificationMessageResponse> resend(@Valid @RequestBody ResendVerificationRequest request,
			HttpServletRequest http) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(verifications.resend(request, CompanySignupController.clientAddress(http)));
	}
}
