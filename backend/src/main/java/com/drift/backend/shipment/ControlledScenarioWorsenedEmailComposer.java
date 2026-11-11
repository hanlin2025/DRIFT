package com.drift.backend.shipment;

import java.time.Duration;
import java.util.List;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

/** Renders only facts from the immutable backend-owned WORSENED snapshot. */
@Component
class ControlledScenarioWorsenedEmailComposer {
	private static final String SUBJECT_PREFIX = "[DRIFT SYNTHETIC DEMO — NOT REAL] ";

	SimpleMailMessage compose(ControlledScenarioSnapshot snapshot, String scenarioShipmentKey, String from,
			String recipient, String frontendBaseUrl) {
		if (snapshot == null || snapshot.stage() != ControlledScenarioStage.WORSENED) {
			throw new IllegalArgumentException("A WORSENED controlled-scenario snapshot is required");
		}
		if (!"A".equals(scenarioShipmentKey) && !"B".equals(scenarioShipmentKey)) {
			throw new IllegalArgumentException("A Scenario A or B shipment key is required");
		}
		ControlledScenarioSnapshot.ShipmentRead shipment = find(snapshot.shipments(), scenarioShipmentKey);
		String body = """
				DRIFT CONTROLLED SYNTHETIC DEMONSTRATION — NOT REAL

				This is a fictional CS203 demonstration. %s has reached WORSENED in its independent scenario session.
				The risk and deadline comparison below were calculated by DRIFT from that session's WORSENED snapshot.

				%s

				Before taking any action, verify the latest arrival estimate and the recorded transshipment-arrival deadline and port applicability with the responsible carrier and operations team. Treat unknown or unavailable values as unknown.

				No real maritime disruption, booking, cargo impact, or missed connection has been established by this fictional demonstration.
				""".formatted(label(scenarioShipmentKey), describe(shipment, frontendBaseUrl));
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(from);
		message.setTo(recipient);
		message.setSubject(SUBJECT_PREFIX + label(scenarioShipmentKey) + " risk worsened");
		message.setText(body);
		return message;
	}

	private static String describe(ControlledScenarioSnapshot.ShipmentRead shipment, String frontendBaseUrl) {
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
				""".formatted(label(shipment.scenarioShipmentKey()), detailUrl, risk, remainingWindow(shipment.remainingConnectionWindow()),
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

	private static String label(String key) {
		return "A".equals(key) ? "Scenario A (CS203-DEMO-A)" : "Scenario B (CS203-DEMO-B)";
	}

	private static ControlledScenarioSnapshot.ShipmentRead find(
			List<ControlledScenarioSnapshot.ShipmentRead> shipments, String key) {
		return shipments.stream().filter(shipment -> key.equals(shipment.scenarioShipmentKey())).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("The WORSENED snapshot must include Scenario " + key));
	}
}
