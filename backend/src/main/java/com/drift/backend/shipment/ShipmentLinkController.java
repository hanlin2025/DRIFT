package com.drift.backend.shipment;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;
import com.drift.backend.shipment.exception.MissingShipmentInformationException;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@Tag(name = "Shipments", description = "Create shipments and their planned transshipment itineraries.")
public class ShipmentLinkController {

	private final ShipmentLinkService links;

	public ShipmentLinkController(ShipmentLinkService links) {
		this.links = links;
	}

	@PatchMapping("/api/shipments/{shipmentId}/link-importer")
	@Operation(summary = "Link a shipment to an importer", description = "Links one shipment owned by the authenticated freight forwarder's company to another active company, "
			+ "or removes the link when importerCompanyId is null. A request with no body is rejected and does not remove the link. "
			+ "The stored column is shipments.importer_company_id. "
			+ "An importer organisation is any other active company, which is the same rule as the organisation search. "
			+ "A user who is not a freight forwarder in that company is forbidden.",
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
					mediaType = "application/json", examples = {
							@ExampleObject(name = "Not the managing freight forwarder", value = """
									{"message":"Only a freight forwarder in the shipment's company can link it to an importer"}
									"""),
							@ExampleObject(name = "No active company", value = """
									{"message":"Your account must belong to an active company to view shipments"}
									""") })),
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
		if (request == null) {
			throw new MissingShipmentInformationException();
		}
		Long importerCompanyId = request.importerCompanyId();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(links.link(user, shipmentId, importerCompanyId));
	}
}
