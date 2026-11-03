package com.drift.backend.shipment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.drift.backend.maritimechange.MaritimeSignal;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The likely cause of this shipment's behaviour, from the AIS arrival estimate, MPA port data, and NEA weather near
 * the port. Calculated when the shipment is read and not stored. Each source family counts once, so two weather
 * readings are not two sources. An MPA expected arrival supports a delay only for a verified vessel match.
 */
@Schema(description = "Likely cause of this shipment's behaviour on this read, from the AIS arrival estimate, MPA port data, and NEA weather near the transshipment port. Calculated when the shipment is read and not stored. VESSEL_BEHIND_SCHEDULE uses AIS only. PORT_CONDITION uses an MPA expected arrival or notice. WEATHER uses NEA weather near the port. MULTIPLE means port data and weather both support a delay. NONE_DETECTED means sources were read and none supports a delay. UNAVAILABLE means no source could be used.")
public record ShipmentDelayAttribution(
		@Schema(example = "PORT_CONDITION")
		Cause cause,
		@Schema(description = "One sentence naming the cause and the sources behind it.", example = "MPA port data supports a delay at the transshipment port.")
		String summary,
		@Schema(description = "Source families that support a delay: AIS, MPA port data, and NEA weather. Each family counts once.", example = "2")
		int corroboratingSources,
		@Schema(description = "The signals that were read. A signal that does not support a delay is kept when it shows a source was checked.")
		List<AttributedSignal> signals,
		@Schema(description = "The connection-risk level before external port and weather signals were applied. Null when they did not change the level.", nullable = true, example = "MEDIUM")
		ShipmentRisk.Level riskRaisedFrom) {

	public enum Cause {
		NONE_DETECTED,
		VESSEL_BEHIND_SCHEDULE,
		PORT_CONDITION,
		WEATHER,
		MULTIPLE,
		UNAVAILABLE
	}

	public enum Source {
		AIS(Family.VESSEL),
		MPA_ETA_REVISION(Family.PORT),
		MPA_OFFICIAL_NOTICE(Family.PORT),
		NEA_WEATHER(Family.WEATHER);

		private final Family family;

		Source(Family family) {
			this.family = family;
		}

		Family family() {
			return family;
		}
	}

	enum Family {
		VESSEL,
		PORT,
		WEATHER
	}

	@Schema(description = "One signal used for the attribution.")
	public record AttributedSignal(
			@Schema(example = "MPA_ETA_REVISION")
			Source source,
			@Schema(description = "How the signal is tied to this shipment. VERIFIED_ASSOCIATION is a vessel match at a compatible place and time. REGIONAL_CONTEXT is weather near the port. Null for AIS, which is the shipment's own vessel.", nullable = true, example = "VERIFIED_ASSOCIATION")
			String association,
			@Schema(description = "True when this signal supports a delay.")
			boolean supportsDelay,
			@Schema(example = "MPA expects the mother vessel 3 hours 0 minutes after its planned arrival.")
			String summary,
			@Schema(description = "When the source published or observed this value, as the source wrote it. Null when unknown.", nullable = true)
			String observedAt,
			@Schema(description = "Where the value came from.", example = "MPA OCEANS-X due to arrive")
			String provenance) {

		public AttributedSignal {
			if (source == null || summary == null || summary.isBlank() || provenance == null || provenance.isBlank()) {
				throw new IllegalArgumentException("A signal needs its source, a summary, and its provenance");
			}
		}
	}

	static final Duration ETA_TOLERANCE = Duration.ofHours(1);
	static final String AIS_PROVENANCE = "AIS arrival estimate";
	private static final Pattern NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");
	private static final List<String> RESTRICTION_WORDS = List.of("closure", "closed", "restriction", "restricted",
			"suspension", "suspended", "congestion", "delay", "diversion", "prohibited");

	public ShipmentDelayAttribution {
		if (cause == null || summary == null || summary.isBlank()) {
			throw new IllegalArgumentException("An attribution needs its cause and summary");
		}
		signals = signals == null ? List.of() : List.copyOf(signals);
		if (corroboratingSources != families(signals).size()) {
			throw new IllegalArgumentException("Corroborating sources are the source families that support a delay");
		}
		if (riskRaisedFrom == ShipmentRisk.Level.HIGH || riskRaisedFrom == ShipmentRisk.Level.CRITICAL) {
			throw new IllegalArgumentException("Only a Low or Medium risk is raised");
		}
	}

	static ShipmentDelayAttribution unavailable() {
		return new ShipmentDelayAttribution(Cause.UNAVAILABLE, "No AIS, port, or weather signal could be used.", 0,
				List.of(), null);
	}

	/**
	 * Attributes from the arrival estimate already calculated on this read and the matched maritime signals.
	 * {@code plannedMotherArrivalAt} and {@code plannedFeederDepartureAt} judge an MPA expected arrival.
	 */
	static ShipmentDelayAttribution of(ArrivalEstimate estimate, List<MaritimeSignal> maritime,
			OffsetDateTime plannedMotherArrivalAt, OffsetDateTime plannedFeederDepartureAt,
			ShipmentDelayAttributionProperties thresholds) {
		List<AttributedSignal> signals = new ArrayList<>();
		AttributedSignal ais = ais(estimate);
		if (ais != null) {
			signals.add(ais);
		}
		int benignWeather = 0;
		for (MaritimeSignal signal : maritime == null ? List.<MaritimeSignal>of() : maritime) {
			switch (signal.sourceType()) {
				case MaritimeSignal.MPA_ETA_REVISION -> signals.add(expectedArrival(signal, plannedMotherArrivalAt,
						plannedFeederDepartureAt));
				case MaritimeSignal.MPA_OFFICIAL_NOTICE -> {
					AttributedSignal notice = notice(signal);
					if (notice != null) {
						signals.add(notice);
					}
				}
				case MaritimeSignal.NEA_WEATHER -> {
					AttributedSignal weather = weather(signal, thresholds);
					if (weather == null) {
						benignWeather++;
					} else {
						signals.add(weather);
					}
				}
				default -> {
					// A source type DRIFT does not attribute from is left out.
				}
			}
		}
		if (benignWeather > 0 && signals.stream().noneMatch(signal -> signal.source() == Source.NEA_WEATHER)) {
			signals.add(new AttributedSignal(Source.NEA_WEATHER, MaritimeSignal.REGIONAL_CONTEXT, false,
					benignWeather + " current NEA " + (benignWeather == 1 ? "reading" : "readings")
							+ " near the port show no adverse weather.",
					null, "NEA via data.gov.sg"));
		}
		if (signals.isEmpty()) {
			return unavailable();
		}
		Set<Family> supporting = families(signals);
		Cause cause = cause(supporting);
		return new ShipmentDelayAttribution(cause, summary(cause), supporting.size(), signals, null);
	}

	ShipmentDelayAttribution raisedFrom(ShipmentRisk.Level level) {
		return new ShipmentDelayAttribution(cause, summary, corroboratingSources, signals, level);
	}

	/** True when a verified MPA expected arrival for this shipment's vessel supports a delay. */
	boolean verifiedPortDelay() {
		return signals.stream().anyMatch(signal -> signal.supportsDelay() && signal.source() == Source.MPA_ETA_REVISION
				&& MaritimeSignal.VERIFIED_ASSOCIATION.equals(signal.association()));
	}

	/** True when port data and at least one other source family both support a delay. */
	boolean portCorroborated() {
		Set<Family> supporting = families(signals);
		return supporting.contains(Family.PORT) && supporting.size() >= 2;
	}

	List<AttributedSignal> supportingExternal() {
		return signals.stream().filter(signal -> signal.supportsDelay() && signal.source() != Source.AIS).toList();
	}

	private static Set<Family> families(List<AttributedSignal> signals) {
		Set<Family> families = EnumSet.noneOf(Family.class);
		for (AttributedSignal signal : signals) {
			if (signal.supportsDelay()) {
				families.add(signal.source().family());
			}
		}
		return families;
	}

	private static Cause cause(Set<Family> supporting) {
		boolean port = supporting.contains(Family.PORT);
		boolean weather = supporting.contains(Family.WEATHER);
		if (port && weather) {
			return Cause.MULTIPLE;
		}
		if (port) {
			return Cause.PORT_CONDITION;
		}
		if (weather) {
			return Cause.WEATHER;
		}
		if (supporting.contains(Family.VESSEL)) {
			return Cause.VESSEL_BEHIND_SCHEDULE;
		}
		return Cause.NONE_DETECTED;
	}

	private static String summary(Cause cause) {
		return switch (cause) {
			case MULTIPLE -> "MPA port data and NEA weather near the port both support a delay.";
			case PORT_CONDITION -> "MPA port data supports a delay at the transshipment port.";
			case WEATHER -> "Adverse NEA weather near the transshipment port supports a delay.";
			case VESSEL_BEHIND_SCHEDULE -> "The mother vessel is behind plan by AIS, and no port or weather signal supports a delay.";
			case NONE_DETECTED -> "No AIS, port, or weather signal that was read supports a delay.";
			case UNAVAILABLE -> "No AIS, port, or weather signal could be used.";
		};
	}

	private static AttributedSignal ais(ArrivalEstimate estimate) {
		if (estimate == null || estimate.unavailableReason() != null || Boolean.TRUE.equals(estimate.possiblyOutdated())) {
			return null;
		}
		boolean behind = estimate.classification() == ArrivalEstimate.Classification.BEHIND_PLAN;
		String summary = switch (estimate.classification()) {
			case BEHIND_PLAN -> "The AIS arrival estimate is " + estimate.difference() + " behind the planned arrival.";
			case AHEAD_OF_PLAN -> "The AIS arrival estimate is " + estimate.difference() + " ahead of the planned arrival.";
			case ON_SCHEDULE -> "The AIS arrival estimate is on schedule.";
		};
		return new AttributedSignal(Source.AIS, null, behind, summary,
				estimate.lastReceivedAt() == null ? null : estimate.lastReceivedAt().toString(), AIS_PROVENANCE);
	}

	private static AttributedSignal expectedArrival(MaritimeSignal signal, OffsetDateTime plannedMotherArrivalAt,
			OffsetDateTime plannedFeederDepartureAt) {
		Instant expected = parse(signal.currentValue());
		Instant previous = parse(signal.previousValue());
		boolean mother = "MOTHER".equals(signal.vesselRole());
		String vessel = mother ? "mother vessel" : "feeder vessel";
		OffsetDateTime plannedTime = mother ? plannedMotherArrivalAt : plannedFeederDepartureAt;
		Instant planned = plannedTime == null ? null : plannedTime.toInstant();
		boolean verified = MaritimeSignal.VERIFIED_ASSOCIATION.equals(signal.association());
		boolean laterThanPlan = expected != null && planned != null && expected.isAfter(planned.plus(ETA_TOLERANCE));
		boolean revisedLater = expected != null && previous != null && expected.isAfter(previous.plus(ETA_TOLERANCE));
		String summary;
		if (expected == null) {
			summary = "MPA published an expected arrival for the " + vessel + " that cannot be read.";
		} else if (laterThanPlan) {
			summary = "MPA expects the " + vessel + " " + duration(Duration.between(planned, expected)) + " after its "
					+ (mother ? "planned arrival." : "planned departure.");
		} else if (revisedLater) {
			summary = "MPA revised the " + vessel + "'s expected arrival " + duration(Duration.between(previous, expected))
					+ " later.";
		} else {
			summary = "MPA expects the " + vessel + " at " + expected + ", which does not indicate a delay.";
		}
		if (!verified) {
			summary += " The vessel match is not verified at this port and time.";
		}
		return new AttributedSignal(Source.MPA_ETA_REVISION, signal.association(),
				verified && (laterThanPlan || revisedLater), summary, signal.sourceEventAt(), signal.provenance());
	}

	private static AttributedSignal notice(MaritimeSignal signal) {
		String text = signal.currentValue() == null ? "" : signal.currentValue().toLowerCase(Locale.ROOT);
		boolean restriction = RESTRICTION_WORDS.stream().anyMatch(text::contains);
		boolean associated = MaritimeSignal.UNCONFIRMED_CONTEXT.equals(signal.association())
				|| MaritimeSignal.VERIFIED_ASSOCIATION.equals(signal.association());
		if (!restriction || !associated || !MaritimeSignal.COMPATIBLE.equals(signal.timeCompatibility())) {
			return null;
		}
		return new AttributedSignal(Source.MPA_OFFICIAL_NOTICE, signal.association(), true,
				"MPA notice for " + signal.location() + ": " + signal.currentValue(), signal.sourceEventAt(),
				signal.provenance());
	}

	private static AttributedSignal weather(MaritimeSignal signal, ShipmentDelayAttributionProperties thresholds) {
		if (!MaritimeSignal.REGIONAL_CONTEXT.equals(signal.association())
				|| !MaritimeSignal.COMPATIBLE.equals(signal.timeCompatibility()) || signal.currentValue() == null) {
			return null;
		}
		String value = signal.currentValue();
		String lower = value.toLowerCase(Locale.ROOT);
		boolean adverse;
		if (lower.startsWith("rainfall ")) {
			adverse = atLeast(value, thresholds.adverseRainfallMmPerHour());
		} else if (lower.startsWith("wind ")) {
			adverse = atLeast(value, thresholds.adverseWindKnots());
		} else {
			adverse = lower.contains("thunder") || lower.contains("heavy");
		}
		if (!adverse) {
			return null;
		}
		return new AttributedSignal(Source.NEA_WEATHER, signal.association(), true,
				"NEA reports " + value + " at " + signal.location() + ", near the port.", signal.sourceEventAt(),
				signal.provenance());
	}

	private static boolean atLeast(String value, double threshold) {
		Matcher number = NUMBER.matcher(value);
		return number.find() && new BigDecimal(number.group(1)).doubleValue() >= threshold;
	}

	static String duration(Duration duration) {
		long minutes = Math.abs(duration.toMinutes());
		return (minutes / 60) + " hours " + (minutes % 60) + " minutes";
	}

	private static Instant parse(String value) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.parse(value);
		} catch (DateTimeParseException exception) {
			return null;
		}
	}
}
