package com.drift.backend.shipment;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.config.OpenApiConfig;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Version 1 authenticated control API for the gated CDG-279 synthetic scenario. */
@RestController
@Tag(name = "Controlled Scenario v1", description = "Manually control an isolated synthetic disruption replay.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
public class ControlledScenarioSessionController {
	private final ControlledScenarioSessionService sessions;

	public ControlledScenarioSessionController(ControlledScenarioSessionService sessions) {
		this.sessions = sessions;
	}

	@PutMapping("/api/scenarios/v1/sessions/{sessionId}")
	@Operation(summary = "Create or read an unstarted controlled scenario session",
			description = "The anchorShipmentId selects one configured A/B demo shipment. The server independently checks the configured bindings and caller authorization. Repeating the same request is idempotent.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Unstarted or existing session", content = @Content(schema = @Schema(implementation = ControlledScenarioSessionResponse.class))),
			@ApiResponse(responseCode = "400", description = "Invalid request"),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token"),
			@ApiResponse(responseCode = "403", description = "User or company is not configured for the demonstration"),
			@ApiResponse(responseCode = "404", description = "Scenario disabled or session/shipment not found"),
			@ApiResponse(responseCode = "503", description = "Scenario configuration or capacity unavailable") })
	public ResponseEntity<ControlledScenarioSessionResponse> create(
			@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sessionId,
			@Valid @RequestBody ControlledScenarioSessionCreateRequest request) {
		if (user == null) throw new SessionEndedException();
		return response(sessions.create(user, sessionId, request.anchorShipmentId()));
	}

	@GetMapping("/api/scenarios/v1/sessions/{sessionId}")
	@Operation(summary = "Read the current controlled scenario session and snapshot")
	public ResponseEntity<ControlledScenarioSessionResponse> get(
			@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sessionId) {
		if (user == null) throw new SessionEndedException();
		return response(sessions.get(user, sessionId));
	}

	@PostMapping("/api/scenarios/v1/sessions/{sessionId}/start")
	@Operation(summary = "Start an unstarted session at BASELINE",
			description = "Requires the current epoch and revision and an Idempotency-Key. A repeated key with identical parameters replays its original outcome.")
	public ResponseEntity<ControlledScenarioSessionResponse> start(
			@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sessionId,
			@RequestHeader(value = "Idempotency-Key", required = false) String commandKey,
			@Valid @RequestBody ControlledScenarioCommandRequest request) {
		if (user == null) throw new SessionEndedException();
		return response(sessions.start(user, sessionId, request, commandKey));
	}

	@PostMapping("/api/scenarios/v1/sessions/{sessionId}/next")
	@Operation(summary = "Advance exactly one stage",
			description = "Requires the current epoch and revision. Concurrent distinct commands against one revision cannot both advance.")
	public ResponseEntity<ControlledScenarioSessionResponse> next(
			@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sessionId,
			@RequestHeader(value = "Idempotency-Key", required = false) String commandKey,
			@Valid @RequestBody ControlledScenarioCommandRequest request) {
		if (user == null) throw new SessionEndedException();
		return response(sessions.next(user, sessionId, request, commandKey));
	}

	@PostMapping("/api/scenarios/v1/sessions/{sessionId}/reset")
	@Operation(summary = "Reset a run to UNSTARTED in a fresh epoch",
			description = "Reset invalidates the active snapshot. Reset while already UNSTARTED is a no-op and does not create another epoch.")
	public ResponseEntity<ControlledScenarioSessionResponse> reset(
			@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sessionId,
			@RequestHeader(value = "Idempotency-Key", required = false) String commandKey,
			@Valid @RequestBody ControlledScenarioCommandRequest request) {
		if (user == null) throw new SessionEndedException();
		return response(sessions.reset(user, sessionId, request, commandKey));
	}

	private static ResponseEntity<ControlledScenarioSessionResponse> response(ControlledScenarioSessionResponse session) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(session);
	}
}
