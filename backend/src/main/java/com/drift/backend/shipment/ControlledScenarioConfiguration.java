package com.drift.backend.shipment;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ControlledScenarioProperties.class)
class ControlledScenarioConfiguration { }
