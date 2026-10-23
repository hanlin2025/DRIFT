package com.drift.backend.shipment;

import java.net.URI;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;

import com.drift.backend.account.Role;

/** Builds a single-recipient text email from immutable transition data. */
@Component
public class ShipmentRiskAlertEmailComposer {

	public SimpleMailMessage compose(ShipmentRiskAlertDelivery delivery, ShipmentRiskAlertMailProperties properties) {
		ShipmentRiskTransition transition = delivery.getTransition();
		Shipment shipment = transition.getShipment();
		String shipmentReference = shipment.getShipmentReference();
		String link = shipmentLink(properties.getFrontendBaseUrl(), delivery.getUser().getRole(), shipment.getId());
		SimpleMailMessage message = new SimpleMailMessage();
		message.setFrom(properties.getFrom().strip());
		message.setTo(delivery.getUser().getEmail().strip());
		message.setSubject("Connection risk " + transition.getRiskLevel() + " for shipment " + headerValue(shipmentReference));
		message.setText("""
				Shipment %s now has %s connection risk.

				%s

				Review the shipment before the planned connection:
				%s
				""".formatted(shipmentReference, transition.getRiskLevel(), transition.getExplanation(), link));
		return message;
	}

	static String shipmentLink(String frontendBaseUrl, Role role, Long shipmentId) {
		if (shipmentId == null) {
			throw new IllegalArgumentException("Shipment identifier is required");
		}
		String path = switch (role) {
			case FREIGHT_FORWARDER -> "/freight-forwarder/shipments/" + shipmentId;
			case IMPORTER -> "/importer/shipments/" + shipmentId;
			case ADMIN -> "/admin/shipments/" + shipmentId;
			case LOGISTICS_MANAGER -> "/logistics-manager/shipments/" + shipmentId;
		};
		String base = frontendBaseUrl.strip().replaceFirst("/+$", "");
		URI link = URI.create(base + path);
		if (!link.isAbsolute() || link.getHost() == null) {
			throw new IllegalArgumentException("Frontend base URL must produce an absolute shipment link");
		}
		return link.toASCIIString();
	}

	private static String headerValue(String value) {
		return value == null ? "" : value.replaceAll("[\\r\\n]+", " ");
	}
}
