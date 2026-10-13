package com.drift.backend.shipment;

import tools.jackson.databind.JsonNode;

/** Which vessel-identity keys an update body actually contained. An absent key is left stored. */
record VesselIdentityPresence(
		boolean motherVesselImo,
		boolean motherVesselMmsi,
		boolean motherVesselOperator,
		boolean feederVesselImo,
		boolean feederVesselMmsi,
		boolean feederVesselOperator) {

	static VesselIdentityPresence omitted() {
		return new VesselIdentityPresence(false, false, false, false, false, false);
	}

	static VesselIdentityPresence from(JsonNode body) {
		return new VesselIdentityPresence(
				body.has("motherVesselImo"),
				body.has("motherVesselMmsi"),
				body.has("motherVesselOperator"),
				body.has("feederVesselImo"),
				body.has("feederVesselMmsi"),
				body.has("feederVesselOperator"));
	}
}
