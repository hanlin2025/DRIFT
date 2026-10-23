package com.drift.backend.shipment;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling infrastructure is available independently of delivery dispatch; both jobs remain flag-gated. */
@Configuration
@EnableScheduling
class ShipmentRiskAlertEvaluationSchedulingConfig {
}
