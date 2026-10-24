package com.drift.backend.shipment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** Runs only in an explicitly requested maintenance process; it is disabled in every normal startup. */
@Component
@ConditionalOnProperty(name = "app.risk-alerts.activation.command", havingValue = "true")
class ShipmentRiskAlertActivationCommand implements ApplicationRunner {
	private static final Logger log = LoggerFactory.getLogger(ShipmentRiskAlertActivationCommand.class);
	private final ShipmentRiskAlertActivationService activation;
	private final ConfigurableApplicationContext context;

	ShipmentRiskAlertActivationCommand(ShipmentRiskAlertActivationService activation, ConfigurableApplicationContext context) {
		this.activation = activation;
		this.context = context;
	}

	@Override
	public void run(ApplicationArguments args) {
		ShipmentRiskAlertActivationResult result = activation.activate();
		log.info("Risk-alert activation command completed: {}", result);
		context.close();
	}
}
