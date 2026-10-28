package com.drift.backend.shipment;

import java.time.Duration;
import java.time.Instant;

/**
 * Normal distribution centred on the straight-line arrival estimate {@code Â}. The spread grows with the remaining
 * voyage {@code τ = Â − t₀} and the position age {@code a = max(0, now − t₀)}, where {@code t₀} is the time the
 * position was stored, not {@code now}: {@code σ = √(σ₀² + (c_v·τ)² + (k_age·a)²)}.
 * See docs/cdg42-miss-probability-decision.md.
 */
final class StraightLineNormalArrival implements ArrivalDistribution {

	/** Base spread in hours for any estimate. Suggested, uncalibrated, see docs/cdg42-miss-probability-decision.md. */
	static final double BASE_SPREAD_HOURS = 2.0;
	/** Spread per hour of remaining voyage. Suggested, uncalibrated, see docs/cdg42-miss-probability-decision.md. */
	static final double SPREAD_PER_VOYAGE_HOUR = 0.10;
	/** Spread per hour of position age. Suggested, uncalibrated, see docs/cdg42-miss-probability-decision.md. */
	static final double SPREAD_PER_AGE_HOUR = 0.25;

	/** Standard scores are clamped to this range before the normal CDF is taken. */
	static final double Z_LIMIT = 8.0;

	private static final double SECONDS_PER_HOUR = 3600.0;

	// Abramowitz and Stegun 7.1.26: erf(x) ≈ 1 − (a₁t + a₂t² + a₃t³ + a₄t⁴ + a₅t⁵)·e^(−x²), t = 1 / (1 + px), x ≥ 0.
	private static final double P = 0.3275911;
	private static final double A1 = 0.254829592;
	private static final double A2 = -0.284496736;
	private static final double A3 = 1.421413741;
	private static final double A4 = -1.453152027;
	private static final double A5 = 1.061405429;

	private final Instant centre;
	private final double spreadHours;

	StraightLineNormalArrival(ArrivalEstimate estimate, Instant now) {
		if (estimate == null || estimate.unavailableReason() != null || estimate.estimatedArrivalAt() == null
				|| estimate.lastReceivedAt() == null) {
			throw new IllegalArgumentException("A straight-line arrival needs an available estimate");
		}
		if (Boolean.TRUE.equals(estimate.possiblyOutdated())) {
			throw new IllegalArgumentException("A possibly outdated estimate is not used");
		}
		if (now == null) {
			throw new IllegalArgumentException("The evaluation time is required");
		}
		this.centre = estimate.estimatedArrivalAt().toInstant();
		Instant observedAt = estimate.lastReceivedAt();
		double voyageHours = hours(Duration.between(observedAt, centre));
		double ageHours = Math.max(0, hours(Duration.between(observedAt, now)));
		this.spreadHours = Math.sqrt(BASE_SPREAD_HOURS * BASE_SPREAD_HOURS
				+ square(SPREAD_PER_VOYAGE_HOUR * voyageHours)
				+ square(SPREAD_PER_AGE_HOUR * ageHours));
	}

	@Override
	public double probabilityArrivingAfter(Instant instant) {
		double marginHours = hours(Duration.between(centre, instant));
		return standardNormalCdf(-marginHours / spreadHours);
	}

	@Override
	public double spreadHours() {
		return spreadHours;
	}

	/** Φ(z) = ½(1 + erf(z / √2)), with z clamped to [−8, 8]. */
	static double standardNormalCdf(double z) {
		double clamped = Math.max(-Z_LIMIT, Math.min(Z_LIMIT, z));
		return 0.5 * (1 + erf(clamped / Math.sqrt(2)));
	}

	private static double erf(double x) {
		double magnitude = Math.abs(x);
		double t = 1 / (1 + P * magnitude);
		double polynomial = t * (A1 + t * (A2 + t * (A3 + t * (A4 + t * A5))));
		double value = 1 - polynomial * Math.exp(-magnitude * magnitude);
		return x < 0 ? -value : value;
	}

	private static double hours(Duration duration) {
		return duration.getSeconds() / SECONDS_PER_HOUR + duration.getNano() / (SECONDS_PER_HOUR * 1_000_000_000);
	}

	private static double square(double value) {
		return value * value;
	}
}
