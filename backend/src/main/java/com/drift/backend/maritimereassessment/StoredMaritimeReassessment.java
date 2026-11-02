package com.drift.backend.maritimereassessment;

import java.time.Instant;

record StoredMaritimeReassessment(
		long id,
		long shipmentId,
		long owningCompanyId,
		String assessmentId,
		String bundleVersion,
		String bundleHash,
		Instant cutoff,
		String provider,
		String model,
		String promptVersion,
		String generationState,
		String validationStatus,
		String outputJson,
		String failureMetadata,
		Instant createdAt) {
}
