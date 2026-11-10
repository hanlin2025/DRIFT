package com.drift.backend.account.signup;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
@Tag(name = "Company signup", description = "Create a company and its first administrator, who verifies their email before signing in.")
public class CompanySignupController {

	private final CompanySignupService signups;

	public CompanySignupController(CompanySignupService signups) {
		this.signups = signups;
	}

	@PostMapping("/api/register/company")
	@Operation(summary = "Create a company and its first administrator",
			description = "Public. In one transaction, creates an inactive company, an ADMIN account for it that is "
					+ "awaiting email verification, and a single-use verification link. The account cannot sign in and the "
					+ "company is not offered for invitations or shipment linking until the email is verified. "
					+ "If the email is already registered, nothing is created and the response is the same as for a new signup. "
					+ "A company code that is already taken is rejected.")
	@ApiResponses({
			@ApiResponse(responseCode = "202", description = "Accepted; the same body whether or not the email was already registered",
					content = @Content(mediaType = "application/json", schema = @Schema(implementation = CompanySignupResponse.class),
							examples = @ExampleObject(value = """
									{"message":"Check your email to verify your account before signing in."}
									"""))),
			@ApiResponse(responseCode = "400", description = "A missing or invalid field (errors.<field>), or a password that breaks the policy (errors.password)",
					content = @Content(mediaType = "application/json", examples = {
							@ExampleObject(name = "Validation", value = """
									{"message":"Registration information is missing or invalid","errors":{"companyCode":"Enter an organisation code using letters, digits, and underscores."}}
									"""),
							@ExampleObject(name = "Password policy", value = """
									{"message":"Password must be at least 8 characters","errors":{"password":"Password must be at least 8 characters"}}
									""") })),
			@ApiResponse(responseCode = "409", description = "Another organisation already has this code",
					content = @Content(mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"An organisation with this code already exists.","errors":{"companyCode":"An organisation with this code already exists."}}
							"""))) })
	public ResponseEntity<CompanySignupResponse> register(@Valid @RequestBody CompanySignupRequest request,
			HttpServletRequest http) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore())
				.body(signups.register(request, clientAddress(http)));
	}

	static String clientAddress(HttpServletRequest request) {
		String address = request.getRemoteAddr();
		return address == null || address.isBlank() ? "unknown" : address;
	}
}
