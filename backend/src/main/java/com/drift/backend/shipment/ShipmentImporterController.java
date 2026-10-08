package com.drift.backend.shipment;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
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
@Tag(name = "Shipments", description = "Create shipments and their planned transshipment itineraries.")
public class ShipmentImporterController {

	private final ShipmentService shipmentService;

	public ShipmentImporterController(ShipmentService shipmentService) {
		this.shipmentService = shipmentService;
	}

	@GetMapping("/api/importer-organisations")
	@Operation(summary = "List importer organisations", description = "Lists active organisations other than the caller's own company. "
			+ "An optional query matches the organisation name. "
			+ "The result is the set a freight forwarder can link to a shipment.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Importer organisations the caller can link", content = @Content(
					mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ImporterOrganisation.class)))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Authenticated account has no active company", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your account must belong to an active company to view shipments"}
							"""))) })
	public ResponseEntity<List<ImporterOrganisation>> organisations(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam(name = "q", required = false) String query) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(shipmentService.importerOrganisations(user, query));
	}

	@PutMapping("/api/shipments/{shipmentId}/importer")
	@Operation(summary = "Link a shipment to an importer", description = "Links one shipment owned by the authenticated freight forwarder's company to an active importer organisation, "
			+ "or removes the link when the organisation is null. "
			+ "The shipment then appears in that importer organisation's portfolio and disappears from the previous importer's portfolio. "
			+ "A freight forwarder from another company, or a user who is not a freight forwarder, is forbidden.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Shipment linked or unlinked", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = ShipmentDetailResponse.class))),
			@ApiResponse(responseCode = "400", description = "Importer organisation is missing, inactive, or is the shipment's own company", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Invalid or inactive importer organisation selected."}
							"""))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Caller cannot link this shipment", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Only a freight forwarder in the shipment's company can link it to an importer"}
							"""))),
			@ApiResponse(responseCode = "404", description = "No shipment with this id exists", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Shipment not found"}
							"""))) })
	public ResponseEntity<ShipmentDetailResponse> link(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId,
			@RequestBody(required = false) LinkImporterRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		Long importerCompanyId = request == null ? null : request.importerCompanyId();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(shipmentService.linkImporter(user, shipmentId, importerCompanyId));
	}
}
