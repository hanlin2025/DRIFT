package com.drift.backend.organisation;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;
import com.drift.backend.organisation.exception.InvalidOrganisationQueryException;

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
@Tag(name = "Organisations", description = "Search importer organisations the authenticated user can link to a shipment.")
public class OrganisationController {

	private final OrganisationService organisations;

	public OrganisationController(OrganisationService organisations) {
		this.organisations = organisations;
	}

	@GetMapping("/api/organisations")
	@Operation(summary = "Search importer organisations", description = "Returns active importer organisations other than the authenticated user's own company. "
			+ "There is no separate organisation-type column: an importer organisation is any other active company. "
			+ "The name filter matches any part of the organisation name, ignoring letter case. "
			+ "Percent and underscore characters in the name are matched literally. "
			+ "Results are ordered by name, then id. Page is zero-based and defaults to 0. "
			+ "Size defaults to 20 and must be from 1 to 100.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Matching importer organisations, possibly an empty page", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = OrganisationPageResponse.class))),
			@ApiResponse(responseCode = "400", description = "The organisation type, page, or size is not valid", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Organisation type must be importer"}
							"""))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Authenticated account has no active company", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your account must belong to an active company to view organisations"}
							"""))) })
	public ResponseEntity<OrganisationPageResponse> search(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@Parameter(description = "Organisation type. Only importer is accepted.", required = true, example = "importer")
			@RequestParam String type,
			@Parameter(description = "Optional case-insensitive match against any part of the organisation name.")
			@RequestParam(required = false) String name,
			@Parameter(description = "Zero-based page index. Defaults to 0.", example = "0")
			@RequestParam(required = false) Integer page,
			@Parameter(description = "Page size from 1 to 100. Defaults to 20.", example = "20")
			@RequestParam(required = false) Integer size,
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) HttpServletRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		rejectBlank(request, "page", InvalidOrganisationQueryException.PAGE);
		rejectBlank(request, "size", InvalidOrganisationQueryException.SIZE);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(organisations.search(user, type, name, page, size));
	}

	private static void rejectBlank(HttpServletRequest request, String name, String message) {
		if (!request.getParameterMap().containsKey(name)) {
			return;
		}
		String value = request.getParameter(name);
		if (value == null || value.isBlank()) {
			throw new InvalidOrganisationQueryException(message);
		}
	}
}
