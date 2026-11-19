package com.drift.backend.maritimeai;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.drift.backend.maritimecontext.MaritimeEvidence;

/** Pure decision over an already assembled evidence bundle. No provider or persistence work happens here. */
final class MaritimeAiEvidenceEligibility {
	private MaritimeAiEvidenceEligibility() { }

	enum Reason {
		ELIGIBLE, UNAPPROVED_AUTHENTIC_EVIDENCE, UNAPPROVED_LIVE_EVIDENCE,
		SYNTHETIC_EVIDENCE_REQUIRED, INSUFFICIENT_EVIDENCE
	}

	record Decision(List<MaritimeEvidence> eligibleEvidence, Reason reason,
			boolean hasUnapprovedAuthenticEvidence, boolean hasUnapprovedLiveEvidence) {
		Decision {
			eligibleEvidence = List.copyOf(eligibleEvidence);
		}
	}

	static Decision ordinary(List<MaritimeEvidence> evidence, boolean authenticRetrospectiveInferenceApproved,
			Set<String> approvedAuthenticSourceTypes, Set<String> approvedAuthenticEvidenceIds,
			Set<String> approvedLiveEvidenceIds) {
		List<MaritimeEvidence> eligible = new ArrayList<>();
		boolean unapprovedAuthentic = false;
		boolean unapprovedLive = false;
		for (MaritimeEvidence item : evidence) {
			if (!isCurrentAndSufficient(item)) continue;
			if ("SYNTHETIC_DEMONSTRATION".equals(item.sourceMode())) eligible.add(item);
			else if ("RETROSPECTIVE_ANALYSIS".equals(item.sourceMode())) {
				if (authenticRetrospectiveInferenceApproved
						&& approvedAuthenticSourceTypes.contains(item.sourceType())
						&& approvedAuthenticEvidenceIds.contains(item.evidenceId())) eligible.add(item);
				else unapprovedAuthentic = true;
			} else if ("LIVE".equals(item.sourceMode())) {
				if (approvedLiveEvidenceIds.contains(item.evidenceId())) eligible.add(item);
				else unapprovedLive = true;
			} // Recorded replay and unknown modes cannot reach inference.
		}
		Reason reason = unapprovedAuthentic ? Reason.UNAPPROVED_AUTHENTIC_EVIDENCE
				: unapprovedLive ? Reason.UNAPPROVED_LIVE_EVIDENCE
				: eligible.isEmpty() ? Reason.INSUFFICIENT_EVIDENCE : Reason.ELIGIBLE;
		return new Decision(eligible, reason, unapprovedAuthentic, unapprovedLive);
	}

	static Decision scenario(List<MaritimeEvidence> evidence) {
		// The source-mode gate covers every record, including stale and insufficient records.
		if (evidence.stream().anyMatch(item -> !"SYNTHETIC_DEMONSTRATION".equals(item.sourceMode()))) {
			return new Decision(List.of(), Reason.SYNTHETIC_EVIDENCE_REQUIRED, false, false);
		}
		List<MaritimeEvidence> eligible = evidence.stream().filter(MaritimeAiEvidenceEligibility::isCurrentAndSufficient).toList();
		return new Decision(eligible, eligible.isEmpty() ? Reason.INSUFFICIENT_EVIDENCE : Reason.ELIGIBLE, false, false);
	}

	private static boolean isCurrentAndSufficient(MaritimeEvidence item) {
		return item.evidenceSufficient() && !"INCOMPATIBLE".equals(item.compatibilityState())
				&& !"STALE".equals(item.freshness());
	}
}
