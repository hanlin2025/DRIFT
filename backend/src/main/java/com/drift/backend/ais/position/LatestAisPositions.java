package com.drift.backend.ais.position;

import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

@Service
public class LatestAisPositions {

	private final VesselObservationRepository observations;

	public LatestAisPositions(VesselObservationRepository observations) {
		this.observations = observations;
	}

	public void record(AisPosition position) {
		observations.save(new VesselObservation(position));
	}

	public Optional<AisPosition> findByMmsi(String mmsi) {
		if (mmsi == null || mmsi.isBlank()) {
			return Optional.empty();
		}
		return observations.findFirstByMmsiOrderByIngestedAtDescIdDesc(mmsi.strip())
				.map(VesselObservation::toPosition);
	}

	public Optional<AisPosition> findByVesselName(String vesselName) {
		if (vesselName == null || vesselName.isBlank()) {
			return Optional.empty();
		}
		return observations.findLatestByVesselName(normalize(vesselName))
				.map(VesselObservation::toPosition);
	}

	static String normalize(String vesselName) {
		return vesselName.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
	}
}
