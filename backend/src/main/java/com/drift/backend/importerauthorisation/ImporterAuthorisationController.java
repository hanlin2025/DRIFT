package com.drift.backend.importerauthorisation;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;
import com.drift.backend.shipment.exception.InvalidImporterOrganisationException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Importer authorisations", description = "Grant, list, and end the importers a forwarder company may serve.")
public class ImporterAuthorisationController {

	private final ImporterAuthorisationService authorisations;

	public ImporterAuthorisationController(ImporterAuthorisationService authorisations) {
		this.authorisations = authorisations;
	}

	@GetMapping("/api/importer-authorisations")
	@Operation(summary = "List active importer authorisations",
			description = "Returns the active importer authorisations for the caller's company. An administrator or freight forwarder of that company may view them.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Active importer authorisations for the caller's company", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = ImporterAuthorisationListResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Caller cannot view this company's importer authorisations", content = @Content(
					mediaType = "application/json", examples = {
							@ExampleObject(name = "Wrong role", value = """
									{"message":"Only an administrator or freight forwarder of this company can view importer authorisations"}
									"""),
							@ExampleObject(name = "No active company", value = """
									{"message":"Importer authorisations require an active company"}
									""") }))
	})
	public ResponseEntity<ImporterAuthorisationListResponse> list(
			@AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authorisations.list(user));
	}

	@PostMapping("/api/importer-authorisations")
	@Operation(summary = "Grant an importer authorisation",
			description = "An administrator of the forwarder company grants or restores an authorisation for another active company. Repeating the grant keeps the same row. Granting does not link a shipment.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The granted or restored authorisation", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = ImporterAuthorisationResponse.class))),
			@ApiResponse(responseCode = "400", description = "Importer company is missing, inactive, or is the caller's own company", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Invalid or inactive importer organisation selected."}
							"""))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Caller cannot change this company's importer authorisations", content = @Content(
					mediaType = "application/json", examples = {
							@ExampleObject(name = "Not an administrator", value = """
									{"message":"Only an administrator of this company can change importer authorisations"}
									"""),
							@ExampleObject(name = "No active company", value = """
									{"message":"Importer authorisations require an active company"}
									""") }))
	})
	public ResponseEntity<ImporterAuthorisationResponse> grant(
			@AuthenticationPrincipal AuthenticatedUser user,
			@RequestBody(required = false) GrantImporterAuthorisationRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		if (request == null) {
			throw new InvalidImporterOrganisationException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(authorisations.grant(user, request.importerCompanyId()));
	}

	@PostMapping("/api/importer-authorisations/{authorisationId}/end")
	@Operation(summary = "End an importer authorisation",
			description = "An administrator of the forwarder company deactivates one of its authorisations. Ending it does not clear shipments already linked to that importer. Repeating the end leaves those shipments unchanged. An authorisation that belongs to another company is not found.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The ended authorisation", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = ImporterAuthorisationResponse.class))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Caller cannot change this company's importer authorisations", content = @Content(
					mediaType = "application/json", examples = {
							@ExampleObject(name = "Not an administrator", value = """
									{"message":"Only an administrator of this company can change importer authorisations"}
									"""),
							@ExampleObject(name = "No active company", value = """
									{"message":"Importer authorisations require an active company"}
									""") })),
			@ApiResponse(responseCode = "404", description = "No authorisation with this id belongs to the caller's company. Another company's authorisation is also not found.", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Importer authorisation not found"}
							""")))
	})
	public ResponseEntity<ImporterAuthorisationResponse> end(
			@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long authorisationId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(authorisations.end(user, authorisationId));
	}
}
