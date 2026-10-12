package com.drift.backend.account.profile;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;

import tools.jackson.databind.JsonNode;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Profile", description = "The signed-in user's own name, password and notification preference.")
public class ProfileController {

	private final ProfileService profiles;

	public ProfileController(ProfileService profiles) {
		this.profiles = profiles;
	}

	@GetMapping("/api/profile")
	@Operation(summary = "View the signed-in profile", description = "Returns the caller's own full name, email, company and role, "
			+ "plus the stored shipment-risk notification choice. "
			+ "Email, company and role come from the account and cannot be edited here. "
			+ "There is no profile for another user.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The caller's profile", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = ProfileView.class))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))) })
	public ResponseEntity<ProfileView> view(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.view(user));
	}

	@PatchMapping("/api/profile")
	@Operation(summary = "Update the signed-in profile", description = "Updates only the caller's own full name, password, and shipment-risk notification choice. "
			+ "Omitted fields stay as they are. "
			+ "A password change requires the current password and a new password that meets the registration password policy, "
			+ "replaces the existing password hash, and ends the current session. "
			+ "A present but blank password field is rejected. "
			+ "The notification fields store whether email is wanted and the minimum shipment-risk level. No email is sent. "
			+ "minimumRiskNotificationLevel must be exactly LOW, MEDIUM, HIGH, or CRITICAL. "
			+ "Email, company, role and a profile photo are rejected, including when one of those fields is sent as null. "
			+ "A different user id is rejected.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Profile saved", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = ProfileView.class))),
			@ApiResponse(responseCode = "400", description = "The name, password, risk level, or a locked field is not acceptable", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Full name is required"}
							"""))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "The body names a different user", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"You can only update your own profile."}
							"""))) })
	public ResponseEntity<ProfileView> update(
			@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = UpdateProfileRequest.class)))
			@RequestBody JsonNode body) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(profiles.update(user, body));
	}
}
