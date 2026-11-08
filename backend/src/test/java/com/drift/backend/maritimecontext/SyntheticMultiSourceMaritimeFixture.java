package com.drift.backend.maritimecontext;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.drift.backend.shipment.ShipmentDetailResponse;

/**
 * Test-scope-only three-source fixture for CDG-248/249 verification.
 *
 * <p>Each adapter supplies one independently-authored fictional source record. The context
 * service, rather than this fixture, assembles the resulting bundle. Nothing here reads a raw
 * collector export or represents a real vessel, notice, weather observation, or shipment.</p>
 */
public final class SyntheticMultiSourceMaritimeFixture {
	public static final String MPA_EVIDENCE_ID = "synthetic-mpa-schedule-001";
	public static final String NOTICE_EVIDENCE_ID = "synthetic-notice-001";
	public static final String NEA_EVIDENCE_ID = "synthetic-nea-rainfall-001";
	public static final String INCOMPATIBLE_NOTICE_EVIDENCE_ID = "synthetic-notice-irrelevant-001";
	private static final Instant ASSESSMENT_CUTOFF = Instant.parse("2026-10-01T00:00:00Z");

	/** Test/demo replay chronology. Every stage uses the same adapters and context service. */
	public enum ReplayStage {
		BASELINE(Instant.parse("2026-09-30T20:45:00Z")),
		ARRIVAL_REVISION(Instant.parse("2026-09-30T21:15:00Z")),
		MARITIME_CONTEXT(Instant.parse("2026-09-30T22:45:00Z"));
		private final Instant cutoff;
		ReplayStage(Instant cutoff) { this.cutoff = cutoff; }
		Instant cutoff() { return cutoff; }
	}

	private SyntheticMultiSourceMaritimeFixture() { }

	public static MaritimeContextService context() {
		return context(true, true, true);
	}

	/** Source switches are test-only: they exercise the same assembly service for availability cases. */
	public static MaritimeContextService context(boolean includeMpa, boolean includeNotice, boolean includeNea) {
		List<MaritimeEvidenceSourceAdapter> adapters = new java.util.ArrayList<>();
		if (includeMpa) adapters.add(SyntheticMultiSourceMaritimeFixture::mpaEvidence);
		if (includeNotice) adapters.add(SyntheticMultiSourceMaritimeFixture::noticeEvidence);
		if (includeNea) adapters.add(SyntheticMultiSourceMaritimeFixture::neaEvidence);
		if (includeNotice) adapters.add(SyntheticMultiSourceMaritimeFixture::incompatibleNoticeEvidence);
		return new MaritimeContextService(true, adapters,
				Clock.fixed(ASSESSMENT_CUTOFF, ZoneOffset.UTC));
	}

	/** A deterministic replay service for Stage 0--2. Assessment generation still uses the
	 * normal authorised controller and persistence path at the MARITIME_CONTEXT stage. */
	public static MaritimeContextService contextForStage(ReplayStage stage) {
		return contextForCutoff(stage.cutoff());
	}

	private static MaritimeContextService contextForCutoff(Instant cutoff) {
		return new MaritimeContextService(true, List.of(SyntheticMultiSourceMaritimeFixture::mpaEvidence,
				SyntheticMultiSourceMaritimeFixture::noticeEvidence, SyntheticMultiSourceMaritimeFixture::neaEvidence,
				SyntheticMultiSourceMaritimeFixture::incompatibleNoticeEvidence), Clock.fixed(cutoff, ZoneOffset.UTC));
	}

	private static List<MaritimeEvidence> mpaEvidence(ShipmentDetailResponse shipment, Instant cutoff) {
		if (!matchesFictionalShipment(shipment) || cutoff.isBefore(Instant.parse("2026-09-30T21:00:00Z"))) return List.of();
		return List.of(new MaritimeEvidence("MPA_ETA_REVISION", "SYNTHETIC_DEMONSTRATION", MPA_EVIDENCE_ID,
				"Synthetic expected-arrival schedule revision", "SYNTHETIC-MPA-REVISION-001", "https://example.invalid/cdg248/mpa",
				"Synthetic MPA-style revision record; source string and retrieval record are fictional.",
				"2026-10-01T06:00:00+08:00", "2026-10-01T06:00:00+08:00", "2026-09-30T21:00:00Z",
				"FRESH", "2026-10-01T05:00:00+08:00", "2026-10-01T06:00:00+08:00", "+60 minutes",
				"COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true, "VERIFIED",
				"Fictional verified association only: the fixture deliberately joins its fictional shipment and vessel. It establishes no real ETA, cargo, booking, or disruption.",
				"Synthetic Multi-source Vessel", "MOTHER", "EXPECTED_ARRIVAL", "VESSEL_OBSERVATION"));
	}

	private static List<MaritimeEvidence> noticeEvidence(ShipmentDetailResponse shipment, Instant cutoff) {
		if (!matchesFictionalShipment(shipment) || cutoff.isBefore(Instant.parse("2026-09-30T21:30:00Z"))) return List.of();
		return List.of(new MaritimeEvidence("MPA_OFFICIAL_NOTICE", "SYNTHETIC_DEMONSTRATION", NOTICE_EVIDENCE_ID,
				"Synthetic port-area maintenance notice", "SYNTHETIC-PMN-001", "https://example.invalid/cdg248/notice",
				"Synthetic Notice 001, page 1: fictional maintenance applies in Synthetic Port Area from 06:00 to 12:00 SGT.",
				"2026-10-01T00:00:00+08:00", "2026-10-01T00:00:00+08:00", "2026-09-30T21:30:00Z",
				"FRESH", null, "Effective 2026-10-01 06:00-12:00 SGT; activity: fictional maintenance; area: Synthetic Port Area.", null,
				"COMPATIBLE", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true, "VERIFIED",
				"Fictional official-notice-style content. It is contextual only and does not establish a vessel impact, causal link, berth, cargo, or missed connection.",
				null, null, null, "PORT_CONTEXT"));
	}

	private static List<MaritimeEvidence> neaEvidence(ShipmentDetailResponse shipment, Instant cutoff) {
		if (!matchesFictionalShipment(shipment) || cutoff.isBefore(Instant.parse("2026-09-30T22:00:00Z"))) return List.of();
		return List.of(new MaritimeEvidence("NEA_WEATHER", "SYNTHETIC_DEMONSTRATION", NEA_EVIDENCE_ID,
				"Synthetic regional rainfall observation", "SYNTHETIC-NEA-RAINFALL-001", "https://example.invalid/cdg248/nea",
				"Synthetic NEA-style rainfall observation for the Synthetic Port Area; regional context only.",
				"2026-10-01T05:00:00+08:00", "2026-10-01T05:00:00+08:00", "2026-09-30T22:00:00Z",
				"FRESH", null, "Synthetic rainfall: 12.0 mm; station/area: Synthetic Port Area.", null,
				"UNKNOWN", "COMPATIBLE", "COMPATIBLE", "COMPATIBLE", true, true, "VERIFIED",
				"Fictional regional weather context only. It does not establish vessel-specific conditions or causation.",
				null, null, null, "REGIONAL_CONTEXT"));
	}

	private static List<MaritimeEvidence> incompatibleNoticeEvidence(ShipmentDetailResponse shipment, Instant cutoff) {
		if (!matchesFictionalShipment(shipment) || cutoff.isBefore(Instant.parse("2026-09-30T22:30:00Z"))) return List.of();
		return List.of(new MaritimeEvidence("MPA_OFFICIAL_NOTICE", "SYNTHETIC_DEMONSTRATION", INCOMPATIBLE_NOTICE_EVIDENCE_ID,
				"Synthetic notice for a different fictional area", "SYNTHETIC-PMN-IRRELEVANT-001", "https://example.invalid/cdg248/notice-irrelevant",
				"Synthetic Notice 002, page 1: fictional work in a different area.", "2026-10-01T00:00:00+08:00",
				"2026-10-01T00:00:00+08:00", "2026-09-30T22:30:00Z", "FRESH", null,
				"Area: Different Synthetic Area.", null, "INCOMPATIBLE", "INCOMPATIBLE", "COMPATIBLE", "INCOMPATIBLE", true, true, "VERIFIED",
				"Fictional incompatible notice retained only to verify exclusion before model selection; it is not relevant to the fictional shipment.",
				null, null, null, "PORT_CONTEXT"));
	}

	private static boolean matchesFictionalShipment(ShipmentDetailResponse shipment) {
		return "Synthetic Multi-source Port".equals(shipment.transshipmentPort())
				&& "Synthetic Multi-source Vessel".equals(shipment.motherVessel())
				&& "9000002".equals(shipment.motherVesselImo())
				&& shipment.plannedMotherArrivalAt() != null
				&& (OffsetDateTime.parse("2026-10-01T05:00:00+08:00").toInstant().equals(shipment.plannedMotherArrivalAt().toInstant())
						|| OffsetDateTime.parse("2026-10-01T06:00:00+08:00").toInstant().equals(shipment.plannedMotherArrivalAt().toInstant()))
				&& "Synthetic Multi-source Feeder".equals(shipment.feederVessel())
				&& shipment.plannedFeederDepartureAt() != null
				&& OffsetDateTime.parse("2026-10-01T14:00:00+08:00").toInstant().equals(shipment.plannedFeederDepartureAt().toInstant());
	}
}
