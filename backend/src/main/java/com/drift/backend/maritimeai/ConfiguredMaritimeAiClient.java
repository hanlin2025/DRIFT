package com.drift.backend.maritimeai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * An opt-in adapter for a governance-approved structured inference gateway. It sends only the bounded
 * evidence package and expects a {@link MaritimeAiInterpretation} JSON object in the response body.
 * It is deliberately not an implicit OpenAI client: provider-specific request formats belong in an
 * approved replacement adapter.
 */
@Component
class ConfiguredMaritimeAiClient implements MaritimeAiClient {
	private final String provider;
	private final String model;
	private final String apiKey;
	private final String endpoint;
	private final ObjectMapper json;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	ConfiguredMaritimeAiClient(@Value("${app.maritime-ai.provider:}") String provider,
			@Value("${app.maritime-ai.model:}") String model,
			@Value("${app.maritime-ai.api-key:}") String apiKey,
			@Value("${app.maritime-ai.endpoint:}") String endpoint, ObjectMapper json) {
		this.provider = provider == null ? "" : provider.strip();
		this.model = model == null ? "" : model.strip();
		this.apiKey = apiKey == null ? "" : apiKey;
		this.endpoint = endpoint == null ? "" : endpoint.strip();
		this.json = json;
	}

	@Override public MaritimeAiClientResult interpret(MaritimeAiEvidencePackage evidence) {
		if (provider.isEmpty() || model.isEmpty() || apiKey.isBlank() || endpoint.isEmpty()) return MaritimeAiClientResult.unavailable("No approved maritime AI provider is configured.");
		try {
			String body = json.writeValueAsString(evidence);
			HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(Duration.ofSeconds(20))
					.header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) return MaritimeAiClientResult.unavailable("Inference gateway returned HTTP " + response.statusCode() + ".");
			return new MaritimeAiClientResult(provider, model, "cdg247.evidence.v1", json.readValue(response.body(), MaritimeAiInterpretation.class), null);
		} catch (Exception ex) {
			return MaritimeAiClientResult.unavailable("Inference gateway failure: " + ex.getClass().getSimpleName());
		}
	}
}
