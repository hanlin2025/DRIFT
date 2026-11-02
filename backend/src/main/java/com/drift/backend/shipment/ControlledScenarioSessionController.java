package com.drift.backend.shipment;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

/** Minimal authenticated control API for the CDG-279 synthetic scenario. */
@RestController
@Tag(name = "Controlled Scenario", description = "Manually control an isolated synthetic disruption replay.")
public class ControlledScenarioSessionController {
	private final ControlledScenarioSessionService sessions;

	public ControlledScenarioSessionController(ControlledScenarioSessionService sessions) {
		this.sessions = sessions;
	}

	@PutMapping("/api/scenarios/cdg279/sessions/{sessionId}")
	@Operation(summary = "Start or read a controlled scenario session",
			description = "Creates the caller-owned session at BASELINE, or returns its current stage when repeated. "
					+ "The response contains one snapshotId and cutoff for shipment risk, impact, AIS provenance, maritime evidence, and optional map playback.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Current session snapshot", content = @Content(schema = @Schema(implementation = ControlledScenarioSnapshot.class))),
			@ApiResponse(responseCode = "401", description = "Missing, expired, or invalid bearer token"),
			@ApiResponse(responseCode = "403", description = "Authenticated user has no active company") })
	public ResponseEntity<ControlledScenarioSnapshot> start(
			@AuthenticationPrincipal AuthenticatedUser user, @PathVariable java.util.UUID sessionId) {
		if (user == null) throw new SessionEndedException();
		return snapshot(sessions.start(user, sessionId));
	}

	@GetMapping("/api/scenarios/cdg279/sessions/{sessionId}")
	@Operation(summary = "Read the current controlled scenario snapshot",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	public ResponseEntity<ControlledScenarioSnapshot> get(
			@AuthenticationPrincipal AuthenticatedUser user, @PathVariable java.util.UUID sessionId) {
		if (user == null) throw new SessionEndedException();
		return snapshot(sessions.get(user, sessionId));
	}

	@PostMapping("/api/scenarios/cdg279/sessions/{sessionId}/next")
	@Operation(summary = "Advance exactly one scenario stage",
			description = "Requires the current snapshotId. A retry with an older snapshotId is idempotent and returns the current state; advancing beyond WORSENED is a conflict.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	public ResponseEntity<ControlledScenarioSnapshot> next(
			@AuthenticationPrincipal AuthenticatedUser user, @PathVariable java.util.UUID sessionId,
			@Valid @RequestBody ControlledScenarioNextRequest request) {
		if (user == null) throw new SessionEndedException();
		return snapshot(sessions.next(user, sessionId, request.expectedSnapshotId()));
	}

	@PostMapping("/api/scenarios/cdg279/sessions/{sessionId}/reset")
	@Operation(summary = "Reset this scenario session to BASELINE",
			description = "Reset is idempotent and returns the same baseline snapshot identity each time for this session.",
			security = @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME))
	public ResponseEntity<ControlledScenarioSnapshot> reset(
			@AuthenticationPrincipal AuthenticatedUser user, @PathVariable java.util.UUID sessionId) {
		if (user == null) throw new SessionEndedException();
		return snapshot(sessions.reset(user, sessionId));
	}

	private static ResponseEntity<ControlledScenarioSnapshot> snapshot(ControlledScenarioSnapshot snapshot) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(snapshot);
	}
}
