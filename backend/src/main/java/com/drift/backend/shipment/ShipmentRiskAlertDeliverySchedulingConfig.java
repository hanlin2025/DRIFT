package com.drift.backend.shipment;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling starts only after the separately controlled delivery-dispatch activation. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.risk-alerts.mail.dispatch-enabled", havingValue = "true")
public class ShipmentRiskAlertDeliverySchedulingConfig {
}
