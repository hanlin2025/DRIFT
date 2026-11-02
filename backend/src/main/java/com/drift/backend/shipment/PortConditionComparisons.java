package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.drift.backend.ais.operation.OperationalStateService;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.LatestAisPositions;

/**
 * Reads the mother vessel's own earlier reports while a shipment response is assembled.
 * The comparison lives in {@link ShipmentDetailResponse#from} so detail and the importer link stay together.
 */
@Service
public class PortConditionComparisons {

	/** Newest earlier reports read for one vessel. A further report makes the baseline unavailable rather than partial. */
	public static final int EARLIER_REPORT_LIMIT = 500;

	private static final ThreadLocal<PortConditionComparisons> CURRENT = new ThreadLocal<>();

	private final LatestAisPositions positions;
	private final OperationalStateService ports;
	private final Clock clock;

	@Autowired
	public PortConditionComparisons(LatestAisPositions positions, OperationalStateService ports) {
		this(positions, ports, Clock.systemUTC());
	}

	PortConditionComparisons(LatestAisPositions positions, OperationalStateService ports, Clock clock) {
		this.positions = positions;
		this.ports = ports;
		this.clock = clock;
	}

	static void use(PortConditionComparisons comparisons) {
		CURRENT.set(comparisons);
	}

	static void clear() {
		CURRENT.remove();
	}

	/**
	 * {@code latest} is the observation the operational state on this response was derived from.
	 * Outside a request there is no history read, and a missing report stays {@code NO_REPORT}.
	 */
	static PortConditionComparison forMother(String vesselName, String transshipmentPort, AisPosition latest) {
		PortConditionComparisons current = CURRENT.get();
		if (current == null) {
			return PortConditionComparison.of(vesselName, transshipmentPort, latest, List.of(), false, Instant.now(),
					new OperationalStateService());
		}
		return current.compare(vesselName, transshipmentPort, latest);
	}

	private PortConditionComparison compare(String vesselName, String transshipmentPort, AisPosition latest) {
		List<AisPosition> earlier = List.of();
		boolean truncated = false;
		if (latest != null && latest.mmsi() != null && !latest.mmsi().isBlank() && latest.ingestedAt() != null) {
			List<AisPosition> fetched = positions.earlier(latest.mmsi(), latest.ingestedAt(), EARLIER_REPORT_LIMIT + 1);
			truncated = fetched.size() > EARLIER_REPORT_LIMIT;
			earlier = truncated ? List.of() : fetched;
		}
		return PortConditionComparison.of(vesselName, transshipmentPort, latest, earlier, truncated, clock.instant(),
				ports);
	}
}
