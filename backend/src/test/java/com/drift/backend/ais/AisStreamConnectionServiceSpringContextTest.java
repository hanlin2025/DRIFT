package com.drift.backend.ais;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.AisPositionParser;
import com.drift.backend.ais.position.AisPositionSource;
import com.drift.backend.ais.position.LatestAisPositions;

import tools.jackson.databind.ObjectMapper;

class AisStreamConnectionServiceSpringContextTest {

	@Test
	void createsTheConditionalServiceWithItsInjectedDependenciesWhenAisIsEnabled() {
		AisPositionParser parser = mock(AisPositionParser.class);
		LatestAisPositions positions = mock(LatestAisPositions.class);
		AisPosition position = new AisPosition("563123456", "CONTEXT TEST VESSEL", new BigDecimal("1.2"),
				new BigDecimal("103.8"), new BigDecimal("8.0"), null, null, null, true, null,
				Instant.parse("2026-11-02T00:00:00Z"), AisPositionSource.AIS_STREAM);
		when(parser.parse("test report")).thenReturn(Optional.of(position));

		try (AnnotationConfigApplicationContext context = context(true, parser, positions)) {
			AisStreamConnectionService service = context.getBean(AisStreamConnectionService.class);

			service.ingest("test report");

			verify(parser).parse("test report");
			verify(positions).record(position);
		}
	}

	@Test
	void doesNotRegisterTheConnectionServiceWhenAisIsDisabled() {
		try (AnnotationConfigApplicationContext context = context(false,
				mock(AisPositionParser.class), mock(LatestAisPositions.class))) {
			assertThat(context.getBeansOfType(AisStreamConnectionService.class)).isEmpty();
		}
	}

	private static AnnotationConfigApplicationContext context(boolean enabled, AisPositionParser parser,
			LatestAisPositions positions) {
		// A plain Spring context does not publish ApplicationReadyEvent, so bean wiring is tested without opening a socket.
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("ais-test-properties",
				Map.of("app.ais.enabled", Boolean.toString(enabled))));
		context.getBeanFactory().registerSingleton("aisStreamProperties", new AisStreamProperties(enabled,
				URI.create("wss://stream.aisstream.io/v0/stream"), "test-only-key", 1.0, 103.0, 1.5, 104.0));
		context.getBeanFactory().registerSingleton("objectMapper", new ObjectMapper());
		context.getBeanFactory().registerSingleton("aisPositionParser", parser);
		context.getBeanFactory().registerSingleton("latestAisPositions", positions);
		context.register(AisStreamConnectionService.class);
		context.refresh();
		return context;
	}
}
