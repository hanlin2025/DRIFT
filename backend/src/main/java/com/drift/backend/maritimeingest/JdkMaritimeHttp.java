package com.drift.backend.maritimeingest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

final class JdkMaritimeHttp implements MaritimeHttp {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);
	private static final String USER_AGENT = "DRIFT-maritime-ingest/1.0";

	private final HttpClient client;

	JdkMaritimeHttp(HttpClient client) {
		this.client = client;
	}

	@Override
	public String get(URI uri, Map<String, String> headers) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET()
				.header("Accept", "application/json")
				.header("User-Agent", USER_AGENT);
		headers.forEach(request::header);
		try {
			HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2) {
				throw new MaritimeSourceException("HTTP " + response.statusCode() + " from " + uri.getHost());
			}
			return response.body();
		} catch (IOException exception) {
			throw new MaritimeSourceException(exception.getClass().getSimpleName() + " from " + uri.getHost());
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new MaritimeSourceException("Interrupted while reading " + uri.getHost());
		}
	}
}
