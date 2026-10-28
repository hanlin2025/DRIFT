package com.drift.backend.shipment.history;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import com.drift.backend.shipment.Shipment;

/**
 * The editable fields of a shipment as text, in a fixed order, so two snapshots can be compared field by field.
 * Times are written as UTC instants so the same moment sent with a different offset is not a change.
 */
public final class ShipmentSnapshot {

	private final Map<String, String> values;

	private ShipmentSnapshot(Map<String, String> values) {
		this.values = Collections.unmodifiableMap(values);
	}

	public static ShipmentSnapshot of(Shipment shipment) {
		Map<String, String> values = new LinkedHashMap<>();
		values.put("shipmentReference", shipment.getShipmentReference());
		values.put("origin", shipment.getOrigin());
		values.put("destination", shipment.getDestination());
		values.put("transshipmentPort", shipment.getTransshipmentPort());
		values.put("motherVessel", shipment.getMotherVessel());
		values.put("plannedMotherArrivalAt", instant(shipment.getPlannedMotherArrivalAt()));
		values.put("motherVesselImo", shipment.getMotherVesselImo());
		values.put("motherVesselMmsi", shipment.getMotherVesselMmsi());
		values.put("motherVesselOperator", shipment.getMotherVesselOperator());
		values.put("feederVessel", shipment.getFeederVessel());
		values.put("plannedFeederDepartureAt", instant(shipment.getPlannedFeederDepartureAt()));
		values.put("feederVesselImo", shipment.getFeederVesselImo());
		values.put("feederVesselMmsi", shipment.getFeederVesselMmsi());
		values.put("feederVesselOperator", shipment.getFeederVesselOperator());
		return new ShipmentSnapshot(values);
	}

	Map<String, String> values() {
		return values;
	}

	private static String instant(OffsetDateTime time) {
		return time == null ? null : time.toInstant().toString();
	}
}
