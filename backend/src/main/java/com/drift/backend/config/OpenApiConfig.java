package com.drift.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

	public static final String BEARER_AUTH_SCHEME = "bearerAuth";

	@Bean
	public OpenAPI driftOpenApi() {
		return new OpenAPI()
				.info(new Info().title("DRIFT API").version("v1")
						.description("API for managing DRIFT freight shipments and transshipment itineraries."))
				.components(new Components().addSecuritySchemes(BEARER_AUTH_SCHEME,
						new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
	}
}
