package com.drift.backend.shipment;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ArrivalEstimateConfiguration {

	@Bean
	PortCatalog portCatalog() {
		return PortCatalog.load();
	}

	@Bean
	ArrivalEstimateCalculator arrivalEstimateCalculator(PortCatalog ports) {
		return new ArrivalEstimateCalculator(ports);
	}
}
