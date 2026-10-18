package com.drift.backend.account.admin;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Account removal", description = "Administrator removal of user accounts.")
public class AccountRemovalController {

	private final AccountRemovalService removals;

	public AccountRemovalController(AccountRemovalService removals) {
		this.removals = removals;
	}

	@PatchMapping("/api/admin/users/{userId}/remove")
	@Operation(summary = "Remove a user account", description = "Marks one user account as removed. The account is kept and is not deleted. "
			+ "The user's existing session stops working. "
			+ "An account that is already removed cannot be removed again.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Account removed", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = AdminUserResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Authenticated account is not an administrator", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Only an administrator can assign users"}
							"""))),
			@ApiResponse(responseCode = "404", description = "No user exists with this id", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"User not found."}
							"""))),
			@ApiResponse(responseCode = "409", description = "The account has already been removed", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"This account has already been removed."}
							"""))) })
	public ResponseEntity<AdminUserResponse> remove(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long userId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(removals.remove(user, userId));
	}
}
