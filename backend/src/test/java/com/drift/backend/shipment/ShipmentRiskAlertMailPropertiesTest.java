package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** Guards the local mail defaults and the suppression reasons the delivery store already records. */
class ShipmentRiskAlertMailPropertiesTest {

	@Test
	void localFromAndFrontendBaseFollowPasswordResetWithoutTurningMailOn() throws IOException {
		String yaml;
		try (var stream = getClass().getClassLoader().getResourceAsStream("application.yaml")) {
			yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}

		assertThat(yaml)
				.contains("enabled: ${RISK_ALERTS_MAIL_ENABLED:false}")
				.contains("dispatch-enabled: ${RISK_ALERTS_DISPATCH_ENABLED:false}")
				.contains("frontend-base-url: ${RISK_ALERTS_FRONTEND_BASE_URL:http://localhost:5173}")
				.contains("from: ${RISK_ALERTS_FROM:noreply@localhost}")
				.contains("from: ${PASSWORD_RESET_FROM:noreply@localhost}");
	}

	@Test
	void disabledMailStaysTheSuppressionReasonAndIsNotARetry() {
		ShipmentRiskAlertMailProperties mail = new ShipmentRiskAlertMailProperties();
		mail.setFrom(" ");
		mail.setFrontendBaseUrl(" ");

		assertThat(mail.isEnabled()).isFalse();
		assertThat(mail.isDispatchEnabled()).isFalse();
		assertThat(mail.unavailableReason(true)).isEqualTo("Risk alert mail is disabled");
		assertThat(mail.unavailableReason(false)).isEqualTo("Risk alert mail is disabled");
	}

	@Test
	void aBlankSenderAddressAndAnUnusableFrontendBaseKeepTheirReasons() {
		ShipmentRiskAlertMailProperties mail = ready();
		mail.setFrom(" ");
		assertThat(mail.unavailableReason(true)).isEqualTo("Risk alert sender address is not configured");

		mail.setFrom("alerts@example.test");
		for (String baseUrl : new String[] { " ", "localhost:5173", "http://localhost:5173?next=1", "http://localhost:5173#mail" }) {
			mail.setFrontendBaseUrl(baseUrl);
			assertThat(mail.unavailableReason(true)).isEqualTo("Risk alert frontend base URL is not configured");
		}
	}

	@Test
	void aConfiguredLocalOriginHasNoSuppressionReason() {
		ShipmentRiskAlertMailProperties mail = ready();

		assertThat(mail.unavailableReason(true)).isNull();
		assertThat(mail.unavailableReason(false)).isEqualTo("Risk alert mail sender is unavailable");
	}

	private static ShipmentRiskAlertMailProperties ready() {
		ShipmentRiskAlertMailProperties mail = new ShipmentRiskAlertMailProperties();
		mail.setEnabled(true);
		mail.setFrom("noreply@localhost");
		mail.setFrontendBaseUrl("http://localhost:5173");
		return mail;
	}
}
