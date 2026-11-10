package com.drift.backend.account.passwordreset;

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
@Tag(name = "Password reset", description = "Request a password reset link by email and choose a new password with it.")
public class PasswordResetController {

	private final PasswordResetService passwordResetService;

	public PasswordResetController(PasswordResetService passwordResetService) {
		this.passwordResetService = passwordResetService;
	}

	@PostMapping("/api/auth/forgot-password")
	@Operation(summary = "Request a password reset link",
			description = "Public. If the email belongs to an account, emails a single-use link of the form "
					+ "{link-base-url}#token=<43-character token> that expires after app.password-reset.ttl (1 hour by default). "
					+ "Requesting again replaces any earlier unused link. The response is the same whether or not "
					+ "the email is registered. Attempts are limited per email and per client address.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Request accepted; the same message for registered and unknown emails",
					content = @Content(mediaType = "application/json", schema = @Schema(implementation = ForgotPasswordResponse.class),
							examples = @ExampleObject(value = """
									{"message":"If this email is registered, a reset link has been sent"}
									"""))),
			@ApiResponse(responseCode = "400", description = "Missing or malformed email", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Password reset information is missing or invalid","errors":{"email":"Email must be a valid email address"}}
							"""))),
			@ApiResponse(responseCode = "429", description = "Too many requests for this email or from this address; "
					+ "the same response whether or not the email is registered", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Too many password reset requests. Try again later.","errors":{}}
							"""))) })
	public ResponseEntity<ForgotPasswordResponse> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
			HttpServletRequest http) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(passwordResetService.request(request, clientAddress(http)));
	}

	@PostMapping("/api/auth/reset-password")
	@Operation(summary = "Choose a new password with a reset link",
			description = "Public. Takes the token from the #token= fragment of an emailed link and a new password. "
					+ "The password is checked against the password policy before the link is used, so a rejected "
					+ "password leaves the link valid. On success the link is used up, the account's other unused links "
					+ "are deleted, and every existing session of the account ends. A used, expired, replaced or unknown link, "
					+ "or a link for a removed account, gets the same invalid-link response. Attempts are limited per client address.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Password replaced; existing sessions have ended",
					content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResetPasswordResponse.class),
							examples = @ExampleObject(value = """
									{"message":"Your password has been reset"}
									"""))),
			@ApiResponse(responseCode = "400", description = "Missing fields, a password that breaks the policy (errors.password), "
					+ "or a malformed, used, expired, replaced or unknown link (errors.token)", content = @Content(
					mediaType = "application/json", examples = {
							@ExampleObject(name = "Invalid or expired link", value = """
									{"message":"This reset link is invalid or has expired. Please request a new one.","errors":{"token":"This reset link is invalid or has expired. Please request a new one."}}
									"""),
							@ExampleObject(name = "Password policy", value = """
									{"message":"Password must be at least 8 characters","errors":{"password":"Password must be at least 8 characters"}}
									"""),
							@ExampleObject(name = "Validation", value = """
									{"message":"Password reset information is missing or invalid","errors":{"password":"Password is required"}}
									""") })),
			@ApiResponse(responseCode = "429", description = "Too many attempts from this address", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Too many password reset requests. Try again later.","errors":{}}
							"""))) })
	public ResponseEntity<ResetPasswordResponse> resetPassword(@Valid @RequestBody ResetPasswordRequest request,
			HttpServletRequest http) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(passwordResetService.reset(request, clientAddress(http)));
	}

	static String clientAddress(HttpServletRequest request) {
		String address = request.getRemoteAddr();
		if (address == null || address.isBlank()) {
			return "unknown";
		}
		return address;
	}
}
