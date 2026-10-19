package com.drift.backend.shipment;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
import io.swagger.v3.oas.annotations.media.SchemaProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import tools.jackson.databind.JsonNode;

@RestController
@Tag(name = "Shipments", description = "Create shipments and their planned transshipment itineraries.")
public class ShipmentController {

	private final ShipmentService shipmentService;
	private final VesselTrackService vesselTracks;

	public ShipmentController(ShipmentService shipmentService, VesselTrackService vesselTracks) {
		this.shipmentService = shipmentService;
		this.vesselTracks = vesselTracks;
	}

	@GetMapping("/api/shipments")
	@Operation(summary = "List shipments", description = "Lists shipments visible to the authenticated user's active company, newest first. "
			+ "This includes shipments managed by that company and shipments linked to it as the importer. "
			+ "Archived shipments are excluded unless the optional status filter requests ARCHIVED. "
			+ "Each shipment includes the planned connection window calculated from its stored schedule, "
			+ "the risk derived from that window in the same way as the shipment detail (null when not assessed), "
			+ "and the linked importer organisation (null when not linked). "
			+ "Optional filters are combined with AND.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Shipments of the company, possibly empty", content = @Content(
						mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ShipmentResponse.class)))),
				@ApiResponse(responseCode = "400", description = "Invalid filter value, including a malformed arrival date or unsupported status", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment information is missing or invalid","errors":{"arrivalDate":"Arrival date must use ISO format YYYY-MM-DD"}}
								"""))),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your account must belong to an active company to view shipments"}
								"""))) })
	public ResponseEntity<List<ShipmentResponse>> list(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@io.swagger.v3.oas.annotations.Parameter(description = "Optional case-insensitive substring search of the shipment reference", example = "HBL-2026") @RequestParam(required = false) String reference,
			@io.swagger.v3.oas.annotations.Parameter(description = "Optional case-insensitive substring search of either the mother vessel or feeder vessel", example = "Pacific") @RequestParam(required = false) String vessel,
			@io.swagger.v3.oas.annotations.Parameter(description = "Optional case-insensitive substring search of the linked importer Company Code or Company Name", example = "IMPORTER-SG") @RequestParam(required = false) String customer,
			@io.swagger.v3.oas.annotations.Parameter(description = "Optional planned mother-arrival date in Asia/Singapore, using ISO YYYY-MM-DD", example = "2026-10-12")
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @RequestParam(required = false) LocalDate arrivalDate,
			@io.swagger.v3.oas.annotations.Parameter(description = "Optional exact shipment status", schema = @Schema(implementation = ShipmentStatus.class), example = "ARCHIVED") @RequestParam(required = false) ShipmentStatus status) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(shipmentService.list(user, ShipmentFilterCriteria.of(reference, vessel, customer, arrivalDate, status)));
	}

	@GetMapping("/api/shipments/{shipmentId}")
	@Operation(summary = "Get a shipment", description = "Returns one shipment visible to the authenticated user's active company, "
			+ "including a shipment linked to that company as the importer, and includes the planned connection window calculated from the stored mother-vessel arrival and feeder-vessel departure, "
			+ "and the latest retained AIS position for each vessel name. "
			+ "A shipment of another company is forbidden, and an unknown id is reported as not found.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Shipment found", content = @Content(
						mediaType = "application/json", schema = @Schema(implementation = ShipmentDetailResponse.class))),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company, or the shipment belongs to another company", content = @Content(
						mediaType = "application/json", schemaProperties = @SchemaProperty(name = "message", schema = @Schema(type = "string")), examples = {
								@ExampleObject(name = "No active company", value = """
										{"message":"Your account must belong to an active company to view shipments"}
										"""),
								@ExampleObject(name = "Shipment of another company", value = """
										{"message":"You do not have access to this shipment"}
										""") })),
				@ApiResponse(responseCode = "404", description = "No shipment exists with this id", content = @Content(
						mediaType = "application/json", schemaProperties = @SchemaProperty(name = "message", schema = @Schema(type = "string")), examples = @ExampleObject(value = """
								{"message":"Shipment not found"}
								"""))) })
	public ResponseEntity<ShipmentDetailResponse> get(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(shipmentService.get(user, shipmentId));
	}

	@GetMapping("/api/shipments/{shipmentId}/tracking")
	@Operation(summary = "Get shipment vessel tracking", description = "Returns the latest retained AIS position for the mother vessel and the feeder vessel "
			+ "of one shipment visible to the authenticated user's active company. "
			+ "Each position includes coordinates, speed, course, heading, and the time it was ingested. "
			+ "A missing position is null. This is a live fix, not an arrival estimate. "
			+ "A name warning is returned for the position in this response when its AIS name differs from the stored vessel name after trimming, collapsing whitespace, and ignoring case. The MV / M/V prefix is kept. "
			+ "A null warning clears any older warning. A shipment of another company is reported as not found.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Tracking for the shipment, with a null position where no AIS report is retained", content = @Content(
						mediaType = "application/json", schema = @Schema(implementation = ShipmentTrackingResponse.class))),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your account must belong to an active company to view shipments"}
								"""))),
				@ApiResponse(responseCode = "404", description = "No shipment with this id belongs to the authenticated user's company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment not found"}
								"""))) })
	public ResponseEntity<ShipmentTrackingResponse> tracking(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(shipmentService.tracking(user, shipmentId));
	}

	@GetMapping("/api/shipments/{shipmentId}/track-history")
	@Operation(summary = "Get vessel track history", description = "Returns the recent track of the mother vessel or the feeder vessel "
			+ "of one shipment visible to the authenticated user's active company, built from stored AIS observations in time order. "
			+ "The window is the last 6, 24 or 72 hours, 24 by default. Observations flagged as invalid positions are left out. "
			+ "At most 500 points are returned; when the window holds more, the newest are kept and truncated is true. "
			+ "Segments slower than slowdownThresholdKnots are slowdowns. "
			+ "A vessel with no retained AIS report returns no points. "
			+ "A shipment of another company is reported as not found.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Track history for the vessel, possibly with no points", content = @Content(
						mediaType = "application/json", schema = @Schema(implementation = VesselTrackResponse.class))),
				@ApiResponse(responseCode = "400", description = "Unknown vessel or unsupported window", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment information is missing or invalid","errors":{"hours":"Window must be 6, 24 or 72 hours"}}
								"""))),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your account must belong to an active company to view shipments"}
								"""))),
				@ApiResponse(responseCode = "404", description = "No shipment with this id is visible to the authenticated user's company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment not found"}
								"""))) })
	public ResponseEntity<VesselTrackResponse> trackHistory(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId,
			@io.swagger.v3.oas.annotations.Parameter(description = "MOTHER or FEEDER; MOTHER by default", example = "MOTHER") @RequestParam(required = false) String vessel,
			@io.swagger.v3.oas.annotations.Parameter(description = "Window length in hours: 6, 24 or 72; 24 by default", example = "24") @RequestParam(required = false) String hours) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(vesselTracks.history(user, shipmentId, vessel, hours));
	}

	@PostMapping("/api/shipments")
	@Operation(summary = "Create a shipment", description = "Creates a shipment for the authenticated user's active company. "
			+ "Shipment references are unique within that company, ignoring letter case. "
			+ "The response includes the planned connection window, calculated from the stored mother-vessel arrival and feeder-vessel departure.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "201", description = "Shipment created", content = @Content(
						mediaType = "application/json", schema = @Schema(implementation = ShipmentResponse.class))),
				@ApiResponse(responseCode = "400", description = "Missing or invalid shipment data, including an invalid itinerary", content = @Content(
						mediaType = "application/json", examples = {
								@ExampleObject(name = "Missing field", value = """
										{"message":"Shipment information is missing or invalid","errors":{"origin":"Origin is required"}}
										"""),
								@ExampleObject(name = "Invalid itinerary", value = """
										{"message":"Planned feeder-vessel departure must be after planned mother-vessel arrival","errors":{"plannedFeederDepartureAt":"Planned feeder-vessel departure must be after planned mother-vessel arrival"}}
										""") })),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your account must belong to an active company to create shipments"}
								"""))),
				@ApiResponse(responseCode = "409", description = "Shipment reference already exists for the authenticated user's company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"A shipment with this reference already exists for your company"}
								"""))) })
	public ResponseEntity<ShipmentResponse> create(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody CreateShipmentRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
				.body(shipmentService.create(user, request));
	}

	@PutMapping("/api/shipments/{shipmentId}")
	@Operation(summary = "Replace a shipment", description = "Replaces the editable shipment itinerary for a shipment managed by the authenticated user's active company. "
			+ "An omitted vessel IMO, MMSI, or operator stays as stored. Sending that field as null or blank clears it. "
			+ "A linked importer may view and track a shipment but cannot replace it. "
			+ "Supply the version returned when the shipment was last read. A stale version returns 409 Conflict; refresh the shipment before trying again. "
			+ "Shipment references remain unique within the company, ignoring letter case.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Shipment updated", content = @Content(
						mediaType = "application/json", schema = @Schema(implementation = ShipmentResponse.class))),
				@ApiResponse(responseCode = "400", description = "Missing or invalid shipment data, including an invalid itinerary or version", content = @Content(
						mediaType = "application/json", examples = {
								@ExampleObject(name = "Invalid itinerary", value = """
										{"message":"Planned feeder-vessel departure must be after planned mother-vessel arrival","errors":{"plannedFeederDepartureAt":"Planned feeder-vessel departure must be after planned mother-vessel arrival"}}
										"""),
								@ExampleObject(name = "Missing version", value = """
										{"message":"Shipment information is missing or invalid","errors":{"version":"Version is required"}}
										""") })),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company, cannot edit the shipment, or the shipment is archived", content = @Content(
						mediaType = "application/json", examples = {
								@ExampleObject(name = "No active company", value = """
										{"message":"Your account must belong to an active company to view shipments"}
										"""),
								@ExampleObject(name = "Archived", value = """
										{"message":"This shipment cannot be edited in its current status."}
										""") })),
				@ApiResponse(responseCode = "404", description = "No shipment with this id belongs to the authenticated user's company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment not found"}
								"""))),
				@ApiResponse(responseCode = "409", description = "Shipment reference already exists for the company, or the supplied version is stale", content = @Content(
						mediaType = "application/json", examples = {
								@ExampleObject(name = "Duplicate reference", value = """
										{"message":"A shipment with this reference already exists for your company"}
										"""),
								@ExampleObject(name = "Stale version", value = """
										{"message":"This shipment was updated by another user. Refresh it and try again."}
										""") })) })
	public ResponseEntity<ShipmentResponse> update(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@io.swagger.v3.oas.annotations.Parameter(description = "Identifier of the shipment to replace", example = "42") @PathVariable Long shipmentId,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = UpdateShipmentRequest.class)))
			@RequestBody JsonNode body) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(shipmentService.update(user, shipmentId, body));
	}

	@PatchMapping("/api/shipments/{shipmentId}/status")
	@Operation(summary = "Archive a shipment", description = "Sets an active shipment of the authenticated user's company to ARCHIVED and keeps the stored record. "
			+ "Only an Admin or Logistics Manager of that company may archive it. "
			+ "The shipment no longer appears in the active list. "
			+ "A shipment of another company is reported as not found. "
			+ "An account with no active company is forbidden.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Shipment archived", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment removed successfully"}
								"""))),
				@ApiResponse(responseCode = "400", description = "Shipment id is not a number, or the status is missing or not ARCHIVED", content = @Content(
						mediaType = "application/json", examples = {
								@ExampleObject(name = "Invalid id", value = """
										{"message":"Invalid Shipment ID."}
										"""),
								@ExampleObject(name = "Invalid status", value = """
										{"message":"Shipment information is missing or invalid","errors":{"status":"Status must be ARCHIVED"}}
										""") })),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Your session has ended. Log in again."}
								"""))),
				@ApiResponse(responseCode = "403", description = "Account has no active company, or the role cannot archive shipments", content = @Content(
						mediaType = "application/json", examples = {
								@ExampleObject(name = "No active company", value = """
										{"message":"Your account must belong to an active company to view shipments"}
										"""),
								@ExampleObject(name = "Role", value = """
										{"message":"You do not have permission to archive this shipment."}
										""") })),
				@ApiResponse(responseCode = "404", description = "No shipment with this id belongs to the authenticated user's company", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment not found"}
								"""))),
				@ApiResponse(responseCode = "409", description = "Shipment is already archived", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Shipment is already removed or does not exist."}
								"""))) })
	public ResponseEntity<Map<String, String>> archive(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long shipmentId,
			@Valid @RequestBody ArchiveShipmentRequest request) {
		if (user == null) {
			throw new SessionEndedException();
		}
		shipmentService.archive(user, shipmentId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(Map.of("message", ShipmentService.REMOVED_MESSAGE));
	}
}
