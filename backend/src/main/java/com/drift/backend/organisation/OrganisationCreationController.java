package com.drift.backend.organisation;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
@Tag(name = "Organisations", description = "Search importer organisations and add an organisation from the administrator workspace.")
public class OrganisationCreationController {

	private final OrganisationCreationService organisations;

	public OrganisationCreationController(OrganisationCreationService organisations) {
		this.organisations = organisations;
	}

	@PostMapping("/api/admin/organisations")
	@Operation(summary = "Add an organisation", description = "An administrator of an active company adds an organisation. "
			+ "The code is the identity. It is stored in uppercase, using letters, digits, and underscores, and it must be unique. "
			+ "The name is the display name. "
			+ "The new organisation is active and has no users, no shipments, and no importer relationship. "
			+ "It can be named by an invitation, and another company can select it in the existing importer search. "
			+ "It is not added to the administrator's own assignment list. "
			+ "Creating it does not invite anyone and does not change who can see an existing shipment. "
			+ "A freight forwarder, importer, or logistics manager cannot add an organisation. "
			+ "An administrator invites and assigns people only inside their own organisation.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Organisation added", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = OrganisationResponse.class))),
			@ApiResponse(responseCode = "400", description = "The code or name is missing or not valid", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Enter an organisation code using letters, digits, and underscores."}
							"""))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "The account is not an administrator, or the administrator has no active company", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Only an administrator can assign users"}
							"""))),
			@ApiResponse(responseCode = "409", description = "An organisation with this code already exists", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"An organisation with this code already exists."}
							"""))) })
	public ResponseEntity<OrganisationResponse> create(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@RequestBody CreateOrganisationRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(organisations.create(user, request));
	}
}
