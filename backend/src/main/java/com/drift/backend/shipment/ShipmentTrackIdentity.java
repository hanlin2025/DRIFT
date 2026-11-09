package com.drift.backend.shipment;

/** Shipment-scoped vessel identity used by recorded track retrieval after shipment visibility is checked. */
public record ShipmentTrackIdentity(
		String motherVesselName,
		String motherImo,
		String motherMmsi,
		String feederVesselName,
		String feederImo,
		String feederMmsi) {

	public String vesselName(TrackVessel vessel) {
		return vessel == TrackVessel.MOTHER ? motherVesselName : feederVesselName;
	}

	public String mmsi(TrackVessel vessel) {
		return vessel == TrackVessel.MOTHER ? motherMmsi : feederMmsi;
	}

	public String imo(TrackVessel vessel) {
		return vessel == TrackVessel.MOTHER ? motherImo : feederImo;
	}
}
