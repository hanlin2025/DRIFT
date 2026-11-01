package com.drift.backend.shipment;

import java.util.Optional;

import org.springframework.stereotype.Service;

import com.drift.backend.ais.operation.OperationalState;
import com.drift.backend.ais.operation.OperationalStateService;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.LatestAisPositions;

/**
 * Derives a vessel's operational state from the same latest retained observation the shipment detail already uses.
 */
@Service
public class ShipmentOperationalStates {

	private static final ThreadLocal<ShipmentOperationalStates> CURRENT = new ThreadLocal<>();

	private final LatestAisPositions positions;
	private final OperationalStateService states;

	public ShipmentOperationalStates(LatestAisPositions positions, OperationalStateService states) {
		this.positions = positions;
		this.states = states;
	}

	static void use(ShipmentOperationalStates states) {
		CURRENT.set(states);
	}

	static void clear() {
		CURRENT.remove();
	}

	/**
	 * Both shipment detail and the importer link use {@link ShipmentDetailResponse#from}.
	 * The request scope supplies this service, so those responses stay on the same observation.
	 */
	static OperationalState forShipmentVessel(String vesselName, String transshipmentPort) {
		return observed(vesselName, transshipmentPort).state();
	}

	/**
	 * The latest retained observation and the operational state derived from it.
	 * One lookup, so the port-condition comparison uses that same report.
	 */
	static ObservedVessel observed(String vesselName, String transshipmentPort) {
		ShipmentOperationalStates current = CURRENT.get();
		if (current == null) {
			return new ObservedVessel(null, new OperationalStateService().derive(null, null, null, null, transshipmentPort));
		}
		return current.observe(vesselName, transshipmentPort);
	}

	public OperationalState forVessel(String vesselName, String transshipmentPort) {
		return observe(vesselName, transshipmentPort).state();
	}

	private ObservedVessel observe(String vesselName, String transshipmentPort) {
		AisPosition position = find(vesselName).orElse(null);
		if (position == null) {
			return new ObservedVessel(null, states.derive(null, null, null, null, transshipmentPort));
		}
		return new ObservedVessel(position, states.derive(position.navigationalStatus(), position.speedOverGroundKnots(),
				position.latitude(), position.longitude(), transshipmentPort, position.ingestedAt()));
	}

	record ObservedVessel(AisPosition position, OperationalState state) {
	}

	private Optional<AisPosition> find(String vesselName) {
		return positions.findByVesselName(vesselName)
				.or(() -> positions.findByVesselName(withoutVesselPrefix(vesselName)));
	}

	private static String withoutVesselPrefix(String vesselName) {
		if (vesselName == null) {
			return null;
		}
		String stripped = vesselName.strip();
		if (stripped.length() > 3 && stripped.regionMatches(true, 0, "MV ", 0, 3)) {
			return stripped.substring(3);
		}
		if (stripped.length() > 4 && stripped.regionMatches(true, 0, "M/V ", 0, 4)) {
			return stripped.substring(4);
		}
		return stripped;
	}
}
