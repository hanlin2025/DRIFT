package com.drift.backend.maritimereassessment;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;

/** Authorized history only. There is no endpoint that starts a provider call. */
@RestController
public class MaritimeReassessmentController {

	private final MaritimeReassessmentService reassessments;

	public MaritimeReassessmentController(MaritimeReassessmentService reassessments) {
		this.reassessments = reassessments;
	}

	@GetMapping("/api/shipments/{shipmentId}/maritime-reassessments")
	public ResponseEntity<MaritimeReassessmentHistory> history(
			@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long shipmentId) {
		if (user == null) {
			throw new SessionEndedException();
		}
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(reassessments.history(user, shipmentId));
	}
}
