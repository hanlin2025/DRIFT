package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

/** A normal test startup leaves the one-time activation command unregistered. */
@SpringBootTest
@ActiveProfiles("test")
class ShipmentRiskAlertActivationCommandIntegrationTests {

	@Autowired ApplicationContext context;

	@Test
	void normalStartupDoesNotRegisterTheActivationCommand() {
		assertThat(context.getBeanNamesForType(ShipmentRiskAlertActivationCommand.class)).isEmpty();
	}
}
