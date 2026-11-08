package com.drift.backend.maritimecontext;

import java.util.List;

public record MaritimeNoticeEvidence(
		String noticeId,
		String title,
		String officialSourceUrl,
		String citation,
		String publishedAt,
		String observedAt,
		String effectiveFrom,
		String effectiveUntil,
		String supersessionStatus,
		String compatibilityState,
		boolean evidenceSufficient,
		boolean humanReviewRequired,
		String shipmentAssociationConfidence,
		List<String> activityEvidence,
		List<String> locationEvidence,
		String limitation) {
}
