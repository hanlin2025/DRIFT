package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.drift.backend.shipment.ArrivalEstimate.Classification;
import com.drift.backend.shipment.RemainingConnectionWindow.Status;

import tools.jackson.databind.JsonNode;

final class RemainingWindowAssertions {

	private RemainingWindowAssertions() {
	}

	static void assertMatches(JsonNode body) {
		OffsetDateTime feederDeparture = OffsetDateTime.parse(body.get("plannedFeederDepartureAt").asText());
		ConnectionWindowCalculation planned = new ConnectionWindowService().calculate(
				OffsetDateTime.parse(body.get("plannedMotherArrivalAt").asText()), feederDeparture);
		RemainingConnectionWindow remaining = remaining(body, feederDeparture);
		ShipmentRisk risk = ShipmentRisk.of(planned, remaining);
		assertThat(body.get("risk").get("level").asText()).isEqualTo(risk.level().name());
		assertThat(body.get("risk").get("explanation").asText()).isEqualTo(risk.explanation());
		JsonNode window = body.get("remainingConnectionWindow");
		assertThat(body.has("remainingConnectionWindow")).isTrue();
		if (remaining == null) {
			assertThat(window.isNull()).isTrue();
			return;
		}
		assertThat(window.get("status").asText()).isEqualTo(remaining.status().name());
		if (remaining.status() == Status.AVAILABLE) {
			assertThat(window.get("duration").asText()).isEqualTo(remaining.duration());
			assertThat(window.get("totalSeconds").asLong()).isEqualTo(remaining.totalSeconds());
			assertThat(window.get("missedBy").isNull()).isTrue();
			assertThat(window.get("missedBySeconds").isNull()).isTrue();
			return;
		}
		assertThat(window.get("duration").isNull()).isTrue();
		assertThat(window.get("totalSeconds").isNull()).isTrue();
		assertThat(window.get("missedBy").asText()).isEqualTo(remaining.missedBy());
		assertThat(window.get("missedBySeconds").asLong()).isEqualTo(remaining.missedBySeconds());
	}

	private static RemainingConnectionWindow remaining(JsonNode body, OffsetDateTime feederDeparture) {
		JsonNode comparison = body.get("arrivalComparison");
		if (comparison == null || comparison.isNull() || comparison.get("unavailableReason") == null
				|| !comparison.get("unavailableReason").isNull() || comparison.get("possiblyOutdated") == null
				|| comparison.get("possiblyOutdated").isNull() || comparison.get("possiblyOutdated").asBoolean()
				|| comparison.get("estimatedArrivalAt") == null || comparison.get("estimatedArrivalAt").isNull()) {
			return null;
		}
		return new ConnectionWindowService().remaining(feederDeparture, ArrivalEstimate.available(
				OffsetDateTime.parse(comparison.get("estimatedArrivalAt").asText()),
				new BigDecimal(comparison.get("speedOverGroundKnots").asText()),
				Classification.valueOf(comparison.get("classification").asText()),
				comparison.get("difference").asText(),
				comparison.get("differenceSeconds").asLong(),
				comparison.get("differenceNanos").asInt(),
				OffsetDateTime.parse(comparison.get("lastReceivedAt").asText()).toInstant(),
				false));
	}
}
