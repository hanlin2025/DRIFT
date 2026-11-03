package com.drift.backend.maritimeingest;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MaritimeIngestProperties.class)
class MaritimeIngestConfig {

	@Bean
	MaritimeHttp maritimeHttp() {
		return new JdkMaritimeHttp(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
	}
}
