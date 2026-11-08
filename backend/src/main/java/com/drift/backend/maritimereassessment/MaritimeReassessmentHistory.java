package com.drift.backend.maritimereassessment;

import java.util.List;

/** History for one shipment. The latest attempt and the latest validated assessment stay distinct. */
public record MaritimeReassessmentHistory(
		long shipmentId,
		MaritimeReassessmentVersion latestAttempt,
		MaritimeReassessmentVersion latestValidated,
		List<MaritimeReassessmentVersion> versions) {

	static MaritimeReassessmentHistory of(long shipmentId, List<StoredMaritimeReassessment> stored) {
		List<MaritimeReassessmentVersion> versions = stored.stream().map(MaritimeReassessmentVersion::from).toList();
		MaritimeReassessmentVersion latestAttempt = versions.isEmpty() ? null : versions.get(0);
		MaritimeReassessmentVersion latestValidated = versions.stream().filter(MaritimeReassessmentVersion::validated)
				.findFirst().orElse(null);
		return new MaritimeReassessmentHistory(shipmentId, latestAttempt, latestValidated, versions);
	}
}
