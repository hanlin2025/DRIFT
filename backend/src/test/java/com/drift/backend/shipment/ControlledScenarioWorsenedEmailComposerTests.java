package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ControlledScenarioWorsenedEmailComposerTests {
	private final ControlledScenarioWorsenedEmailComposer composer = new ControlledScenarioWorsenedEmailComposer();

	@Test
	void rendersUnknownSnapshotEvidenceAsUnknownWithoutInventingMargins() {
		var a = shipment("A", 41L, ShipmentRisk.Level.HIGH,
				new DelayComparison(null, DelayComparison.Reason.MISSING_CONSTRAINT,
						"No deadline is recorded.", null, null, null), null);
		var b = shipment("B", 42L, null,
				new DelayComparison(null, DelayComparison.Reason.MISSING_PREDICTION,
						"The estimate is unavailable.", null, null, null), null);
		SimpleMailMessageText renderedA = render("A", a, b);
		assertThat(renderedA.body()).contains("Scenario A (CS203-DEMO-A)", "HIGH", "Unknown/unavailable — MISSING_CONSTRAINT",
				"Remaining connection window: Unavailable", "No real maritime disruption");
		assertThat(renderedA.body()).doesNotContain("Scenario B", "overrun:", "time before the deadline:");
		SimpleMailMessageText renderedB = render("B", a, b);
		assertThat(renderedB.body()).contains("Scenario B (CS203-DEMO-B)", "Unknown in the snapshot", "MISSING_PREDICTION");
		assertThat(renderedB.body()).doesNotContain("Scenario A", "overrun:", "time before the deadline:");
	}

	@Test
	void computesShipmentSpecificDeadlineMarginsOnlyFromBackendComparisonTimestamps() {
		OffsetDateTime estimate = OffsetDateTime.parse("2026-10-02T08:00:00Z");
		var a = shipment("A", 41L, ShipmentRisk.Level.HIGH,
				new DelayComparison(DelayComparison.Outcome.EXCEEDS_DEADLINE, null, "Exceeds.", estimate,
						estimate.minusHours(20), "Singapore"),
				new RemainingConnectionWindow(RemainingConnectionWindow.Status.AVAILABLE, "4 hours", 14_400L, null, null));
		var b = shipment("B", 42L, ShipmentRisk.Level.MEDIUM,
				new DelayComparison(DelayComparison.Outcome.NO_PREDICTED_IMPACT, null, "No impact.", estimate,
						estimate.plusHours(2), "Singapore"),
				new RemainingConnectionWindow(RemainingConnectionWindow.Status.AVAILABLE, "6 hours", 21_600L, null, null));
		SimpleMailMessageText renderedA = render("A", a, b);
		assertThat(renderedA.subject()).contains("Scenario A (CS203-DEMO-A)");
		assertThat(renderedA.body()).contains("Backend-calculated connection risk: HIGH", "EXCEEDS_DEADLINE", "20 hours",
				"Remaining connection window: AVAILABLE — 4 hours");
		assertThat(renderedA.body()).doesNotContain("Scenario B", "NO_PREDICTED_IMPACT", "6 hours");
		SimpleMailMessageText renderedB = render("B", a, b);
		assertThat(renderedB.subject()).contains("Scenario B (CS203-DEMO-B)");
		assertThat(renderedB.body()).contains("Backend-calculated connection risk: MEDIUM", "NO_PREDICTED_IMPACT", "2 hours",
				"Remaining connection window: AVAILABLE — 6 hours");
		assertThat(renderedB.body()).doesNotContain("Scenario A", "EXCEEDS_DEADLINE", "20 hours");
	}

	private SimpleMailMessageText render(String key, ControlledScenarioSnapshot.ShipmentRead a,
			ControlledScenarioSnapshot.ShipmentRead b) {
		ControlledScenarioSnapshot snapshot = new ControlledScenarioSnapshot(UUID.randomUUID(), UUID.randomUUID(), 3,
				ControlledScenarioStage.WORSENED, 2, 3, "WORSENED", Instant.EPOCH, Instant.EPOCH,
				List.of(b, a), List.of(), null);
		var message = composer.compose(snapshot, key, "noreply@example.test", "approved@example.test", "https://drift.example.test");
		return new SimpleMailMessageText(message.getSubject(), message.getText());
	}

	private static ControlledScenarioSnapshot.ShipmentRead shipment(String key, Long id, ShipmentRisk.Level level,
			DelayComparison comparison, RemainingConnectionWindow remaining) {
		ShipmentRisk risk = level == null ? null : new ShipmentRisk(level, "Backend explanation.");
		return new ControlledScenarioSnapshot.ShipmentRead(key, id, "FIXTURE-" + key, "Singapore", null, null,
				null, remaining, null, risk, comparison);
	}

	private record SimpleMailMessageText(String subject, String body) { }
}
