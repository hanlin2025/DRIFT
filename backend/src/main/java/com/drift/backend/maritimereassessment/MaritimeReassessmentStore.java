package com.drift.backend.maritimereassessment;

import java.util.List;
import java.util.function.Supplier;

interface MaritimeReassessmentStore {

	<T> T exclusively(long shipmentId, String bundleHash, Supplier<T> work);

	List<StoredMaritimeReassessment> forBundle(long shipmentId, String bundleHash);

	StoredMaritimeReassessment insert(MaritimeReassessmentAttempt attempt);

	List<StoredMaritimeReassessment> history(long shipmentId);
}
