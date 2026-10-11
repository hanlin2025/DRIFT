package com.drift.backend.shipment.csvimport;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
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
@Tag(name = "Shipment imports", description = "Asynchronous CSV shipment-import jobs.")
public class ShipmentImportController {

	private static final Set<String> ACCEPTED_CONTENT_TYPES = Set.of("text/csv", "application/csv",
			"application/vnd.ms-excel", "application/octet-stream", "text/plain");

	private final ShipmentImportJobService jobs;
	private final long maximumUploadBytes;

	public ShipmentImportController(ShipmentImportJobService jobs,
			@Value("${app.shipment-import.max-upload-bytes}") long maximumUploadBytes) {
		this.jobs = jobs;
		this.maximumUploadBytes = maximumUploadBytes;
	}

	@PostMapping(path = "/api/shipments/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(summary = "Start a shipment CSV import", description = "Accepts the canonical shipment CSV template and returns immediately with an asynchronous import job. "
			+ "Only FREIGHT_FORWARDER users with an active company are currently permitted. ADMIN import access is deferred until the company type model exists.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "202", description = "Import job accepted", content = @Content(
						mediaType = "application/json", schema = @Schema(implementation = ShipmentImportJobResponse.class))),
				@ApiResponse(responseCode = "400", description = "Missing, empty, oversized, or non-CSV upload", content = @Content(
						mediaType = "application/json", examples = @ExampleObject(value = """
								{"message":"Upload a non-empty .csv file"}
								"""))),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token"),
				@ApiResponse(responseCode = "403", description = "Bulk import is not permitted for this account role or company") })
	public ResponseEntity<ShipmentImportJobResponse> submit(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
					mediaType = MediaType.MULTIPART_FORM_DATA_VALUE))
			@RequestParam(value = "file", required = false) MultipartFile file) {
		if (user == null) {
			throw new SessionEndedException();
		}
		validateEnvelope(file);
		try {
			ShipmentImportJobResponse job = jobs.submit(user, normalizedFilename(file), file.getSize(),
					file.getContentType(), file.getBytes());
			return ResponseEntity.status(HttpStatus.ACCEPTED).cacheControl(CacheControl.noStore()).body(job);
		} catch (IOException exception) {
			throw new InvalidShipmentImportUploadException("CSV upload could not be read");
		}
	}

	@GetMapping("/api/shipments/import/{jobId}")
	@Operation(summary = "Get shipment import status", description = "Returns lightweight job progress and counts. Row errors are available from the separate errors endpoint.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Job visible to the active managing company", content = @Content(
						mediaType = "application/json", schema = @Schema(implementation = ShipmentImportJobResponse.class))),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token"),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company"),
				@ApiResponse(responseCode = "404", description = "Job does not exist or belongs to another company") })
	public ResponseEntity<ShipmentImportJobResponse> status(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long jobId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jobs.status(user, jobId));
	}

	@GetMapping("/api/shipments/import/{jobId}/errors")
	@Operation(summary = "Get shipment import row errors", description = "Returns structured row errors for a completed import job. This endpoint is separate from status polling and is not an error-report download.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
				@ApiResponse(responseCode = "200", description = "Structured row errors", content = @Content(
						mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ShipmentImportJobErrorResponse.class)))),
				@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token"),
				@ApiResponse(responseCode = "403", description = "Authenticated account has no active company"),
				@ApiResponse(responseCode = "404", description = "Job does not exist or belongs to another company") })
	public ResponseEntity<List<ShipmentImportJobErrorResponse>> errors(
			@io.swagger.v3.oas.annotations.Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable Long jobId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(jobs.errors(user, jobId));
	}

	private void validateEnvelope(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new InvalidShipmentImportUploadException("Upload a non-empty .csv file");
		}
		if (file.getSize() > maximumUploadBytes) {
			throw new InvalidShipmentImportUploadException("CSV upload must not exceed 5 MB");
		}
		String filename = normalizedFilename(file);
		if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
			throw new InvalidShipmentImportUploadException("Upload a .csv file");
		}
		String contentType = file.getContentType();
		if (contentType != null && !contentType.isBlank()
				&& !ACCEPTED_CONTENT_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
			throw new InvalidShipmentImportUploadException("Upload a CSV file");
		}
	}

	private String normalizedFilename(MultipartFile file) {
		String filename = file.getOriginalFilename();
		if (filename == null || filename.isBlank()) {
			throw new InvalidShipmentImportUploadException("CSV filename is required");
		}
		String normalized = filename.replace('\\', '/');
		int lastSlash = normalized.lastIndexOf('/');
		normalized = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
		if (normalized.isBlank() || normalized.length() > 255) {
			throw new InvalidShipmentImportUploadException("CSV filename is invalid");
		}
		return normalized;
	}
}
