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
			description = "An administrator of the forwarder company deactivates one of its authorisations. Ending it does not clear shipments already linked to that importer. Repeating the end leaves those shipments unchanged.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
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
