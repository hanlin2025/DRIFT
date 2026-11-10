package com.drift.backend.maritimecontext;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.drift.backend.shipment.ShipmentDetailResponse;

@Service
public class MaritimeContextService {
	private final boolean demoEnabled;
	private final List<MaritimeEvidenceSourceAdapter> adapters;
	private final Clock clock;
	private final Instant configuredCutoff;

	@Autowired
	public MaritimeContextService(@Value("${app.maritime-context.demo-enabled:false}") boolean demoEnabled,
			List<MaritimeEvidenceSourceAdapter> adapters,
			@Value("${app.maritime-context.assessment-cutoff:}") String assessmentCutoff,
			@Value("${app.maritime-context.controlled-replay-enabled:false}") boolean controlledReplayEnabled) {
		this(demoEnabled, adapters, Clock.systemUTC(), replayCutoff(assessmentCutoff, controlledReplayEnabled));
	}

	public MaritimeContextService(boolean demoEnabled, List<MaritimeEvidenceSourceAdapter> adapters) {
		this(demoEnabled, adapters, Clock.systemUTC(), null);
	}

	MaritimeContextService(boolean demoEnabled, List<MaritimeEvidenceSourceAdapter> adapters, Clock clock) {
		this(demoEnabled, adapters, clock, null);
	}

	MaritimeContextService(boolean demoEnabled, List<MaritimeEvidenceSourceAdapter> adapters, Clock clock, Instant configuredCutoff) {
		this.demoEnabled = demoEnabled;
		this.adapters = List.copyOf(adapters);
		this.clock = clock;
		this.configuredCutoff = configuredCutoff;
	}

	public MaritimeContextResponse forShipment(ShipmentDetailResponse shipment) {
		if (!demoEnabled) return forShipment(shipment, clock.instant());
		Instant now = clock.instant();
		boolean replayShipment = configuredCutoff != null && adapters.stream()
				.anyMatch(adapter -> adapter.usesConfiguredAssessmentCutoff(shipment));
		List<MaritimeEvidence> evidence = adapters.stream().flatMap(adapter -> adapter.forShipment(shipment,
				replayShipment && adapter.usesConfiguredAssessmentCutoff(shipment) ? configuredCutoff : now).stream()).toList();
		return responseForEvidence(evidence, replayShipment ? configuredCutoff : now);
	}

	/** A configured cutoff is a local replay control. Startup rejects it unless controlled replay is enabled. */
	static Instant replayCutoff(String value, boolean controlledReplayEnabled) {
		Instant cutoff = parseCutoff(value);
		if (cutoff != null && !controlledReplayEnabled) {
			throw new IllegalStateException("app.maritime-context.assessment-cutoff requires app.maritime-context.controlled-replay-enabled=true");
		}
		return cutoff;
	}

	private static Instant parseCutoff(String value) {
		if (value == null || value.isBlank()) return null;
		try {
			return Instant.parse(value.trim());
		} catch (DateTimeParseException ex) {
			throw new IllegalStateException("app.maritime-context.assessment-cutoff must be an ISO-8601 instant");
		}
	}

	MaritimeContextResponse forShipment(ShipmentDetailResponse shipment, Instant assessmentCutoffUtc) {
		if (!demoEnabled) {
			return new MaritimeContextResponse("cdg246.maritime-evidence-bundle.v1", false, "DISABLED", assessmentCutoffUtc.toString(), "UNAVAILABLE",
					"Maritime context is disabled. No live maritime intelligence has been queried.", List.of(), List.of(), null, null);
		}
		List<MaritimeEvidence> evidence = adapters.stream()
				.flatMap(adapter -> adapter.forShipment(shipment, assessmentCutoffUtc).stream()).toList();
		return responseForEvidence(evidence, assessmentCutoffUtc);
	}

	/** Read-only scenario evidence projection using the same evidence bundle and provenance rules. */
	public MaritimeContextResponse forSyntheticScenario(SyntheticDisruptionScenarioFixture.Scenario scenario,
			SyntheticDisruptionScenarioFixture.ShipmentInput shipment, Instant assessmentCutoffUtc) {
		if (scenario == null || shipment == null || assessmentCutoffUtc == null) {
			throw new IllegalArgumentException("A validated scenario shipment and assessment cutoff are required.");
		}
		scenario.validate();
		List<MaritimeEvidence> evidence = scenario.eligibleEvidenceFor(shipment, assessmentCutoffUtc);
		return responseForEvidence(evidence, assessmentCutoffUtc);
	}

	private MaritimeContextResponse responseForEvidence(List<MaritimeEvidence> evidence, Instant assessmentCutoffUtc) {
		List<MaritimeNoticeEvidence> notices = evidence.stream().filter(item -> "MPA_OFFICIAL_NOTICE".equals(item.sourceType()))
				.map(item -> new MaritimeNoticeEvidence(
						item.evidenceId(), item.title(), item.sourceUrl(), item.citation(), item.sourceEventAt(), item.observedAt(),
						null, null, "UNKNOWN", item.compatibilityState(), item.evidenceSufficient(), item.humanReviewRequired(),
						item.shipmentAssociationConfidence(), List.of(item.activityCompatibility()), List.of(item.locationCompatibility()), item.limitation()))
				.toList();
		String association = shipmentAssociationConfidence(evidence);
		return new MaritimeContextResponse("cdg246.maritime-evidence-bundle.v1", true, bundleMode(evidence), assessmentCutoffUtc.toString(), association,
				"Human review is required. This bundle is a read-only replay/demo and does not change ETA, shipment, risk, alerts, or operational decisions.",
				evidence, notices, null, null);
	}

	private static String bundleMode(List<MaritimeEvidence> evidence) {
		if (evidence.stream().anyMatch(item -> "RETROSPECTIVE_ANALYSIS".equals(item.sourceMode()))) return "RETROSPECTIVE_ANALYSIS";
		if (evidence.stream().anyMatch(item -> "RECORDED_REPLAY".equals(item.sourceMode()))) return "RECORDED_REPLAY";
		if (evidence.stream().anyMatch(item -> "SYNTHETIC_DEMONSTRATION".equals(item.sourceMode()))) return "SYNTHETIC_DEMONSTRATION";
		return "UNAVAILABLE";
	}

	private static String shipmentAssociationConfidence(List<MaritimeEvidence> evidence) {
		if (evidence.stream().anyMatch(item -> "UNVERIFIED_IMO_ONLY".equals(item.shipmentAssociationConfidence()))) {
			return "UNVERIFIED_IMO_ONLY";
		}
		// VERIFIED is deliberately available only to a controlled, all-synthetic fixture.
		// A recorded or live source is never promoted merely because an adapter supplied that string.
		if (!evidence.isEmpty()
				&& evidence.stream().allMatch(item -> "SYNTHETIC_DEMONSTRATION".equals(item.sourceMode())
						&& "VERIFIED".equals(item.shipmentAssociationConfidence()))) {
			return "VERIFIED";
		}
		return "UNAVAILABLE";
	}
}
