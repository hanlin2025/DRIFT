package com.drift.backend.shipment;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

/** Renders only facts from the immutable backend-owned WORSENED snapshot. */
@Component
class ControlledScenarioWorsenedEmailComposer {
	static final String SUBJECT = "[DRIFT SYNTHETIC DEMO — NOT REAL] Scenario A/B risk worsened";

	SimpleMailMessage compose(ControlledScenarioSnapshot snapshot, String from, String recipient, String frontendBaseUrl) {
		if (snapshot == null || snapshot.stage() != ControlledScenarioStage.WORSENED) {
			throw new IllegalArgumentException("A WORSENED controlled-scenario snapshot is required");
		}
		List<ControlledScenarioSnapshot.ShipmentRead> shipments = snapshot.shipments().stream()
				.sorted(Comparator.comparing(ControlledScenarioSnapshot.ShipmentRead::scenarioShipmentKey))
				.toList();
		ControlledScenarioSnapshot.ShipmentRead a = find(shipments, "A");
		ControlledScenarioSnapshot.ShipmentRead b = find(shipments, "B");
		String body = """
				DRIFT CONTROLLED SYNTHETIC DEMONSTRATION — NOT REAL

				This is a fictional CS203 demonstration. The controlled scenario has reached WORSENED.
				The risk and deadline comparisons below were calculated by DRIFT from the WORSENED snapshot.

				%s

				%s

				Key difference between the shipments:
				%s

				Before taking any action, verify the latest arrival estimate and the recorded transshipment-arrival deadline and port applicability with the responsible carrier and operations team. Treat unknown or unavailable values as unknown.

				No real maritime disruption, booking, cargo impact, or missed connection has been established by this fictional demonstration.
				""".formatted(describe(a, frontendBaseUrl), describe(b, frontendBaseUrl), difference(a, b));
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(recipient);
		message.setSubject(SUBJECT);
		message.setText(body);
		return message;
	}

	private static String describe(ControlledScenarioSnapshot.ShipmentRead shipment, String frontendBaseUrl) {
		String label = shipment.scenarioShipmentKey().equals("A") ? "Scenario A (CS203-DEMO-A)" : "Scenario B (CS203-DEMO-B)";
		String risk = shipment.risk() == null || shipment.risk().level() == null
				? "Unknown in the snapshot"
				: shipment.risk().level().name() + (shipment.risk().explanation() == null ? "" : " — " + shipment.risk().explanation());
		String detailUrl = frontendBaseUrl.replaceAll("/+$", "") + "/freight-forwarder/shipments/" + shipment.sourceShipmentId();
		return """
				%s
				Shipment detail and user-triggered AI assessment: %s
				Backend-calculated connection risk: %s
				Remaining connection window: %s
				Deadline comparison: %s
				""".formatted(label, detailUrl, risk, remainingWindow(shipment.remainingConnectionWindow()),
					deadlineComparison(shipment.businessImpact()));
	}

	private static String remainingWindow(RemainingConnectionWindow window) {
		if (window == null) return "Unavailable in the WORSENED snapshot; no value was inferred.";
		return switch (window.status()) {
			case AVAILABLE -> "AVAILABLE — " + window.duration() + " (" + window.totalSeconds() + " seconds).";
			case MISSED -> "MISSED — the backend estimate is after the planned feeder departure by "
					+ window.missedBy() + " (" + window.missedBySeconds() + " seconds).";
		};
	}

	private static String deadlineComparison(DelayComparison comparison) {
		if (comparison == null) return "Unknown — the snapshot contains no deadline comparison.";
		if (comparison.outcome() == null) {
			String reason = comparison.reason() == null ? "UNKNOWN" : comparison.reason().name();
			return "Unknown/unavailable — " + reason + ". " + textOrUnknown(comparison.message());
		}
		String text = comparison.outcome().name() + " — " + textOrUnknown(comparison.message());
		if (comparison.estimatedArrivalAt() == null || comparison.deadline() == null) return text;
		Duration margin = switch (comparison.outcome()) {
			case EXCEEDS_DEADLINE -> Duration.between(comparison.deadline().toInstant(), comparison.estimatedArrivalAt().toInstant());
			case NO_PREDICTED_IMPACT -> Duration.between(comparison.estimatedArrivalAt().toInstant(), comparison.deadline().toInstant());
		};
		if (margin.isNegative()) return text;
		String marginText = ConnectionWindow.durationText(margin);
		return switch (comparison.outcome()) {
			case EXCEEDS_DEADLINE -> text + " Backend-calculated overrun: " + marginText + " (" + margin.toSeconds() + " seconds).";
			case NO_PREDICTED_IMPACT -> text + " Backend-calculated time before the deadline: " + marginText + " (" + margin.toSeconds() + " seconds).";
		};
	}

	private static String textOrUnknown(String text) {
		return text == null || text.isBlank() ? "No explanatory message is available." : text;
	}

	private static String difference(ControlledScenarioSnapshot.ShipmentRead a, ControlledScenarioSnapshot.ShipmentRead b) {
		List<String> differences = new ArrayList<>();
		if (a.risk() != null && b.risk() != null && a.risk().level() != b.risk().level()) {
			differences.add("Scenario A is " + a.risk().level() + " while Scenario B is " + b.risk().level() + " risk.");
		}
		DelayComparison aImpact = a.businessImpact();
		DelayComparison bImpact = b.businessImpact();
		if (aImpact != null && bImpact != null && aImpact.outcome() != bImpact.outcome()) {
			differences.add("A's deadline comparison is " + (aImpact.outcome() == null ? "unknown" : aImpact.outcome())
					+ ", while B's is " + (bImpact.outcome() == null ? "unknown" : bImpact.outcome()) + ".");
		}
		RemainingConnectionWindow aWindow = a.remainingConnectionWindow();
		RemainingConnectionWindow bWindow = b.remainingConnectionWindow();
		if (aWindow != null && bWindow != null && (aWindow.status() != bWindow.status()
				|| !java.util.Objects.equals(aWindow.totalSeconds(), bWindow.totalSeconds()))) {
			differences.add("The remaining connection windows also differ (A: " + remainingWindow(aWindow)
					+ " B: " + remainingWindow(bWindow) + ").");
		}
		if (differences.isEmpty()) return "The WORSENED snapshot does not establish a different risk level or completed deadline outcome for A and B.";
		return String.join(" ", differences);
	}

	private static ControlledScenarioSnapshot.ShipmentRead find(
			List<ControlledScenarioSnapshot.ShipmentRead> shipments, String key) {
		return shipments.stream().filter(shipment -> key.equals(shipment.scenarioShipmentKey())).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("The WORSENED snapshot must include Scenario " + key));
	}
}
