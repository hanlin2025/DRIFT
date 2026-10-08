package com.drift.backend.shipment;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;
import com.drift.backend.shipment.exception.InvalidShipmentFileException;

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
public class ShipmentImportController {

	private static final MediaType CSV = MediaType.parseMediaType("text/csv");

	private final ShipmentImportService imports;

	public ShipmentImportController(ShipmentImportService imports) {
		this.imports = imports;
	}

	@GetMapping("/api/shipment-imports/template")
	@Operation(summary = "Download the shipment CSV template", description = "Returns the CSV header row a freight forwarder uses to import shipments. "
			+ "Planned times include a UTC offset. The importer organisation column is the company name and may be left blank.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "CSV template"),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Caller is not a freight forwarder in an active company", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Only a freight forwarder in an active company can import shipments"}
							"""))) })
	public ResponseEntity<byte[]> template(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(CSV)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shipment-import-template.csv\"")
				.body(imports.template(user));
	}

	@PostMapping(value = "/api/shipment-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Import shipments from a CSV file", description = "Validates each row, creates the valid shipments, and links an importer organisation when its name is supplied. "
			+ "Invalid rows are skipped. The summary reports how many rows were imported and how many failed. "
			+ "The error report for this import lists the row number and the reason.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Import finished", content = @Content(
					mediaType = "application/json", schema = @Schema(implementation = ShipmentImportResponse.class))),
			@ApiResponse(responseCode = "400", description = "The file is missing, too large, or does not use the template headers", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Invalid file format. Please download and use the provided CSV template."}
							"""))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Caller is not a freight forwarder in an active company", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Only a freight forwarder in an active company can import shipments"}
							"""))) })
	public ResponseEntity<ShipmentImportResponse> upload(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@RequestParam("file") MultipartFile file) {
		if (user == null) {
			throw new SessionEndedException();
		}
		byte[] bytes;
		try {
			bytes = file == null ? null : file.getBytes();
		} catch (java.io.IOException ex) {
			throw new InvalidShipmentFileException();
		}
		String filename = file == null ? null : file.getOriginalFilename();
		String contentType = file == null ? null : file.getContentType();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(imports.importCsv(user, bytes, filename, contentType));
	}

	@GetMapping("/api/shipment-imports/{importId}/errors")
	@Operation(summary = "Download a shipment import error report", description = "Returns the row number and reason for each skipped row of one import belonging to the caller's company.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "CSV error report"),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Your session has ended. Log in again."}
							"""))),
			@ApiResponse(responseCode = "403", description = "Caller is not a freight forwarder in an active company", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Only a freight forwarder in an active company can import shipments"}
							"""))),
			@ApiResponse(responseCode = "404", description = "No import with this id belongs to the caller's company", content = @Content(
					mediaType = "application/json", examples = @ExampleObject(value = """
							{"message":"Import not found"}
							"""))) })
	public ResponseEntity<byte[]> errors(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long importId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(CSV)
				.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"shipment-import-errors.csv\"")
				.body(imports.errorReport(user, importId));
	}
}
