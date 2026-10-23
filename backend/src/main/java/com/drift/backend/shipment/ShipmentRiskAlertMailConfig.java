package com.drift.backend.shipment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ShipmentRiskAlertMailProperties.class)
public class ShipmentRiskAlertMailConfig {
}
