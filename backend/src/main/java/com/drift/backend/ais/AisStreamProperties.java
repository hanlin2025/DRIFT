package com.drift.backend.ais;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ais")
public record AisStreamProperties(
		boolean enabled,
		URI streamUri,
		String apiKey,
		Double southwestLatitude,
		Double southwestLongitude,
		Double northeastLatitude,
		Double northeastLongitude) {
}
