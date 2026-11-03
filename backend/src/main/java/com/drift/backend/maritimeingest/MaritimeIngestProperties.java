package com.drift.backend.maritimeingest;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Live maritime collection. Everything stays off until {@code enabled} is set, and each source can be
 * switched off on its own. OCEANS-X expected arrivals also need a subscribed key, a base, and a path.
 */
@ConfigurationProperties(prefix = "app.maritime-ingest")
public record MaritimeIngestProperties(
		boolean enabled,
		Nea nea,
		MpaNotices mpaNotices,
		MpaOceansx mpaOceansx) {

	public MaritimeIngestProperties {
		nea = nea == null ? new Nea(false, null, null, List.of(), 0) : nea;
		mpaNotices = mpaNotices == null ? new MpaNotices(false, null, null, 0) : mpaNotices;
		mpaOceansx = mpaOceansx == null ? new MpaOceansx(null, null, null, 0) : mpaOceansx;
	}

	/** NEA readings from data.gov.sg. Only areas and stations within {@code portRadiusKm} of a listed port are kept. */
	public record Nea(
			boolean enabled,
			URI baseUri,
			String apiKey,
			List<String> ports,
			double portRadiusKm) {

		public Nea {
			ports = ports == null ? List.of() : List.copyOf(ports);
		}
	}

	/** Public MPA port marine notice listing. No key is needed. */
	public record MpaNotices(
			boolean enabled,
			URI listingUri,
			String category,
			int limit) {
	}

	/** OCEANS-X due-to-arrive feed. {@code dueToArrivePath} contains {@code {date}}. */
	public record MpaOceansx(
			String apiKey,
			String baseUri,
			String dueToArrivePath,
			int lookaheadDays) {

		boolean configured() {
			return apiKey != null && !apiKey.isBlank() && baseUri != null && !baseUri.isBlank()
					&& dueToArrivePath != null && !dueToArrivePath.isBlank();
		}
	}
}
