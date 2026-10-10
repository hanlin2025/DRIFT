package com.drift.backend.account.admin;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "User assignment", description = "Administrator assignment of organisations and roles.")
public class AdminAssignmentController {

	private final AdminAssignmentService assignments;

	public AdminAssignmentController(AdminAssignmentService assignments) {
		this.assignments = assignments;
	}

	@GetMapping("/api/admin/users")
	@Operation(summary = "List users for assignment", description = "Lists every user, ordered by name then id, so an administrator can choose who to assign. "
			+ "Each user includes the current role and organisation.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Users, possibly empty", content = @Content(
					mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AdminUserResponse.class)))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Authenticated account is not an administrator", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Only an administrator can assign users"}
							"""))) })
	public ResponseEntity<List<AdminUserResponse>> users(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(assignments.users(user));
	}

	@GetMapping("/api/admin/roles")
	@Operation(summary = "List roles for assignment", description = "Lists the roles an administrator can assign. The code is the stored value and the label is the display name.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Roles", content = @Content(
					mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AdminRoleResponse.class)))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Authenticated account is not an administrator", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Only an administrator can assign users"}
							"""))) })
	public ResponseEntity<List<AdminRoleResponse>> roles(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(assignments.roles(user));
	}

	@GetMapping("/api/admin/organisations")
	@Operation(summary = "List organisations for assignment", description = "Lists active organisations, ordered by name then id. "
			+ "An organisation is a company. Inactive organisations are omitted and cannot be assigned.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Active organisations", content = @Content(
					mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = AdminOrganisationResponse.class)))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Authenticated account is not an administrator", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Only an administrator can assign users"}
							"""))) })
	public ResponseEntity<List<AdminOrganisationResponse>> organisations(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(assignments.organisations(user));
	}

	@PatchMapping("/api/admin/users/{userId}/assign")
	@Operation(summary = "Assign a user to an organisation and role", description = "Stores the organisation and role for one user. "
			+ "The organisation must be an active company and the role must be one of the stored roles. "
			+ "A company can have only one administrator. "
			+ "When the role or organisation changes, the user's existing session stops working and they must log in again. "
			+ "Saving the same organisation and role leaves the current session valid.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Assignment saved", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = AssignmentResponse.class))),
			@ApiResponse(responseCode = "400", description = "The role does not exist or the organisation is missing or inactive", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Invalid role or organisation selected."}
							"""))),
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
			@ApiResponse(responseCode = "409", description = "The organisation already has an administrator", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"A company can have only one admin."}
							"""))) })
	public ResponseEntity<AssignmentResponse> assign(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long userId,
			@RequestBody AssignUserRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(assignments.assign(user, userId, request));
	}
}
