package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.drift.backend.maritimechange.MaritimeSignal;
import com.drift.backend.shipment.ShipmentDelayAttribution.Cause;
import com.drift.backend.shipment.ShipmentDelayAttribution.Source;

class ShipmentDelayAttributionTest {

	private static final OffsetDateTime ARRIVAL = OffsetDateTime.parse("2026-11-03T08:00:00Z");
	private static final OffsetDateTime DEPARTURE = OffsetDateTime.parse("2026-11-03T20:00:00Z");
	private static final ShipmentDelayAttributionProperties THRESHOLDS = ShipmentDelayAttributionProperties.defaults();

	@Test
	void noSignalIsUnavailable() {
		ShipmentDelayAttribution attribution = attribute(null, List.of());

		assertThat(attribution.cause()).isEqualTo(Cause.UNAVAILABLE);
		assertThat(attribution.signals()).isEmpty();
		assertThat(attribution.corroboratingSources()).isZero();
	}

	@Test
	void anOnScheduleEstimateDetectsNoCause() {
		ShipmentDelayAttribution attribution = attribute(estimate(ArrivalEstimate.Classification.ON_SCHEDULE), List.of());

		assertThat(attribution.cause()).isEqualTo(Cause.NONE_DETECTED);
		assertThat(attribution.signals()).singleElement().satisfies(signal -> {
			assertThat(signal.source()).isEqualTo(Source.AIS);
			assertThat(signal.supportsDelay()).isFalse();
		});
	}

	@Test
	void anAisDelayAloneIsTheVesselBehindSchedule() {
		ShipmentDelayAttribution attribution = attribute(estimate(ArrivalEstimate.Classification.BEHIND_PLAN), List.of());

		assertThat(attribution.cause()).isEqualTo(Cause.VESSEL_BEHIND_SCHEDULE);
		assertThat(attribution.corroboratingSources()).isEqualTo(1);
	}

	@Test
	void anOutdatedEstimateIsNotAnAisSignal() {
		ArrivalEstimate outdated = ArrivalEstimate.available(ARRIVAL.plusHours(4), BigDecimal.TEN,
				ArrivalEstimate.Classification.BEHIND_PLAN, "4 hours", 14_400, 0, Instant.parse("2026-11-02T00:00:00Z"), true);

		assertThat(attribute(outdated, List.of()).cause()).isEqualTo(Cause.UNAVAILABLE);
	}

	@Test
	void aVerifiedLaterMpaArrivalIsAPortCause() {
		ShipmentDelayAttribution attribution = attribute(null,
				List.of(eta("VERIFIED_ASSOCIATION", "MOTHER", ARRIVAL.plusHours(3).toInstant(), null)));

		assertThat(attribution.cause()).isEqualTo(Cause.PORT_CONDITION);
		assertThat(attribution.verifiedPortDelay()).isTrue();
		assertThat(attribution.signals()).singleElement().satisfies(signal -> {
			assertThat(signal.supportsDelay()).isTrue();
			assertThat(signal.summary()).isEqualTo("MPA expects the mother vessel 3 hours 0 minutes after its planned arrival.");
		});
	}

	@Test
	void anUnverifiedMpaArrivalIsShownButDoesNotSupportADelay() {
		ShipmentDelayAttribution attribution = attribute(null,
				List.of(eta("UNVERIFIED_IMO_ONLY", "MOTHER", ARRIVAL.plusHours(3).toInstant(), null)));

		assertThat(attribution.cause()).isEqualTo(Cause.NONE_DETECTED);
		assertThat(attribution.signals()).singleElement().satisfies(signal -> {
			assertThat(signal.supportsDelay()).isFalse();
			assertThat(signal.summary()).endsWith("The vessel match is not verified at this port and time.");
		});
	}

	@Test
	void aVerifiedRevisionLaterThanBeforeSupportsADelayWithinThePlan() {
		Instant previous = ARRIVAL.minusHours(4).toInstant();
		ShipmentDelayAttribution attribution = attribute(null,
				List.of(eta("VERIFIED_ASSOCIATION", "MOTHER", ARRIVAL.toInstant(), previous)));

		assertThat(attribution.cause()).isEqualTo(Cause.PORT_CONDITION);
		assertThat(attribution.signals().getFirst().summary())
				.isEqualTo("MPA revised the mother vessel's expected arrival 4 hours 0 minutes later.");
	}

	@Test
	void adverseRegionalWeatherIsAWeatherCause() {
		ShipmentDelayAttribution attribution = attribute(null, List.of(weather("Thundery Showers")));

		assertThat(attribution.cause()).isEqualTo(Cause.WEATHER);
		assertThat(attribution.signals()).singleElement().extracting(ShipmentDelayAttribution.AttributedSignal::summary)
				.isEqualTo("NEA reports Thundery Showers at Tuas, near the port.");
	}

	@Test
	void readingsAreAdverseFromTheConfiguredThresholds() {
		assertThat(attribute(null, List.of(weather("Wind 24.1 knots"))).cause()).isEqualTo(Cause.WEATHER);
		assertThat(attribute(null, List.of(weather("Rainfall 12.0 mm/h"))).cause()).isEqualTo(Cause.WEATHER);
		assertThat(attribute(null, List.of(weather("Wind 10.0 knots"))).cause()).isEqualTo(Cause.NONE_DETECTED);
	}

	@Test
	void benignWeatherIsSummarisedAsOneSignalThatWasChecked() {
		ShipmentDelayAttribution attribution = attribute(null, List.of(weather("Partly Cloudy (Day)"), weather("Wind 5.0 knots")));

		assertThat(attribution.cause()).isEqualTo(Cause.NONE_DETECTED);
		assertThat(attribution.signals()).singleElement().satisfies(signal -> {
			assertThat(signal.source()).isEqualTo(Source.NEA_WEATHER);
			assertThat(signal.supportsDelay()).isFalse();
			assertThat(signal.summary()).isEqualTo("2 current NEA readings near the port show no adverse weather.");
		});
	}

	@Test
	void weatherOutsideItsPeriodIsNotAdverse() {
		MaritimeSignal stale = new MaritimeSignal(1L, MaritimeSignal.NEA_WEATHER, "nea:forecast:Tuas", "REGIONAL_CONTEXT",
				"NONE", "INCOMPATIBLE", "Tuas", null, "Thundery Showers", null, null, null, Instant.EPOCH, "NEA");

		assertThat(attribute(null, List.of(stale)).cause()).isEqualTo(Cause.NONE_DETECTED);
	}

	@Test
	void aRestrictionNoticeAtThePortIsAPortCause() {
		ShipmentDelayAttribution attribution = attribute(null, List.of(notice("Temporary closure of Tuas fairway")));

		assertThat(attribution.cause()).isEqualTo(Cause.PORT_CONDITION);
	}

	@Test
	void anUnrelatedNoticeIsLeftOut() {
		assertThat(attribute(null, List.of(notice("Change of office hours"))).signals()).isEmpty();
	}

	@Test
	void portDataAndWeatherTogetherAreMultipleAndCountEachFamilyOnce() {
		ShipmentDelayAttribution attribution = attribute(estimate(ArrivalEstimate.Classification.BEHIND_PLAN),
				List.of(notice("Port congestion at Tuas"), weather("Thundery Showers"), weather("Heavy Rain")));

		assertThat(attribution.cause()).isEqualTo(Cause.MULTIPLE);
		assertThat(attribution.corroboratingSources()).isEqualTo(3);
		assertThat(attribution.portCorroborated()).isTrue();
	}

	private static ShipmentDelayAttribution attribute(ArrivalEstimate estimate, List<MaritimeSignal> signals) {
		return ShipmentDelayAttribution.of(estimate, signals, ARRIVAL, DEPARTURE, THRESHOLDS);
	}

	static ArrivalEstimate estimate(ArrivalEstimate.Classification classification) {
		return ArrivalEstimate.available(ARRIVAL.plusHours(2), BigDecimal.TEN, classification, "2 hours", 7_200, 0,
				Instant.parse("2026-11-03T00:00:00Z"), false);
	}

	static MaritimeSignal eta(String association, String role, Instant expected, Instant previous) {
		return new MaritimeSignal(1L, MaritimeSignal.MPA_ETA_REVISION, "mpa:due-to-arrive:9351048", association, role,
				"COMPATIBLE", "Singapore", previous == null ? null : previous.toString(), expected.toString(),
				"2026-11-03 14:00:00", null, null, Instant.EPOCH, "MPA OCEANS-X due to arrive");
	}

	static MaritimeSignal weather(String value) {
		return new MaritimeSignal(1L, MaritimeSignal.NEA_WEATHER, "nea:forecast:Tuas", "REGIONAL_CONTEXT", "NONE",
				"COMPATIBLE", "Tuas", null, value, "2026-11-03T08:00:00+08:00", null, null, Instant.EPOCH,
				"NEA via data.gov.sg");
	}

	static MaritimeSignal notice(String title) {
		return new MaritimeSignal(1L, MaritimeSignal.MPA_OFFICIAL_NOTICE, "mpa:notice:1", "UNCONFIRMED_CONTEXT", "NONE",
				"COMPATIBLE", "Tuas, Singapore", null, title, "2026-11-01", null, null, Instant.EPOCH, "MPA notice listing");
	}
}
