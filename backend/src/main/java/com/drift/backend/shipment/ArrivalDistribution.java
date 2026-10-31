package com.drift.backend.shipment;

import java.time.Instant;

/**
 * Spread of possible mother-vessel arrival times around one arrival estimate. The late-arrival chance reads only this,
 * so a real arrival prediction can replace {@link StraightLineNormalArrival} without changing the API fields.
 * See docs/cdg42-miss-probability-decision.md.
 */
interface ArrivalDistribution {

	/** Chance in [0, 1] that the mother vessel arrives strictly after the given instant. */
	double probabilityArrivingAfter(Instant instant);

	/** Spread of the distribution in hours, as used for {@link #probabilityArrivingAfter}. */
	double spreadHours();
}
