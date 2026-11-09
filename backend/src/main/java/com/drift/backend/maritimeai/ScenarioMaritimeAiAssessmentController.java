package com.drift.backend.maritimeai;

import java.util.UUID;

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

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Explicitly triggered, persisted AI interpretation for one authorized synthetic scenario snapshot. */
@RestController
@Tag(name = "Controlled Scenario AI", description = "Persist and retrieve an interpretation for an authorized synthetic scenario snapshot.")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH_SCHEME)
public class ScenarioMaritimeAiAssessmentController {
	private final ScenarioMaritimeAiAssessmentService assessments;

	public ScenarioMaritimeAiAssessmentController(ScenarioMaritimeAiAssessmentService assessments) {
		this.assessments = assessments;
	}

	@PostMapping("/api/scenarios/v1/sessions/{sessionId}/shipments/{shipmentId}/interpretation")
	public ResponseEntity<ScenarioMaritimeAiResponse> generate(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable UUID sessionId, @PathVariable Long shipmentId,
			@Valid @RequestBody ScenarioMaritimeAiRequest request) {
		if (user == null) throw new SessionEndedException();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(assessments.generate(user, sessionId, shipmentId, request));
	}

	@GetMapping("/api/scenarios/v1/sessions/{sessionId}/shipments/{shipmentId}/assessments/{assessmentId}")
	public ResponseEntity<ScenarioMaritimeAiResponse> retrieve(@AuthenticationPrincipal AuthenticatedUser user,
			@PathVariable UUID sessionId, @PathVariable Long shipmentId, @PathVariable String assessmentId) {
		if (user == null) throw new SessionEndedException();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(assessments.retrieve(user, sessionId, shipmentId, assessmentId));
	}
}
