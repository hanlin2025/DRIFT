package com.drift.backend.maritimeai;

import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;

@RestController
public class MaritimeAiAssessmentController {
	private final MaritimeAiAssessmentService assessments;
	public MaritimeAiAssessmentController(MaritimeAiAssessmentService assessments) { this.assessments = assessments; }
	@GetMapping("/api/shipments/{shipmentId}/maritime-ai-assessments")
	public ResponseEntity<List<MaritimeAiAssessmentResponse>> list(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long shipmentId) {
		if (user == null) throw new SessionEndedException();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(assessments.list(user, shipmentId));
	}
	@PostMapping("/api/shipments/{shipmentId}/maritime-ai-assessments")
	public ResponseEntity<MaritimeAiAssessmentResponse> generate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable Long shipmentId) {
		if (user == null) throw new SessionEndedException();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(assessments.generate(user, shipmentId));
	}
}
