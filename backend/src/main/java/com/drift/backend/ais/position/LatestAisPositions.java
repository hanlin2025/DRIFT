package com.drift.backend.ais.position;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class LatestAisPositions {

	private final VesselObservationRepository observations;
	private final ConcurrentHashMap<String, AisPosition> byMmsi = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> mmsiByVesselName = new ConcurrentHashMap<>();

	public LatestAisPositions(VesselObservationRepository observations) {
		this.observations = observations;
	}

	public void record(AisPosition position) {
		observations.save(new VesselObservation(position));
		AisPosition stored = byMmsi.merge(position.mmsi(), position, (current, incoming) ->
				incoming.ingestedAt().isBefore(current.ingestedAt()) ? current : incoming);
		if (stored == position && position.vesselName() != null) {
			rememberNewestName(position);
		}
	}

	public Optional<AisPosition> findByMmsi(String mmsi) {
		if (mmsi == null || mmsi.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(byMmsi.get(mmsi.strip()));
	}

	public Optional<AisPosition> findByVesselName(String vesselName) {
		if (vesselName == null || vesselName.isBlank()) {
			return Optional.empty();
		}
		String normalized = normalize(vesselName);
		String mmsi = mmsiByVesselName.get(normalized);
		Optional<AisPosition> inMemory = mmsi == null ? Optional.empty() : findByMmsi(mmsi);
		Optional<AisPosition> stored = observations.findLatestByNormalizedVesselName(normalized)
				.map(VesselObservation::toAisPosition);
		if (inMemory.isEmpty()) {
			return stored;
		}
		if (stored.isEmpty() || !inMemory.get().ingestedAt().isBefore(stored.get().ingestedAt())) {
			return inMemory;
		}
		return stored;
	}

	private void rememberNewestName(AisPosition position) {
		String normalized = normalize(position.vesselName());
		mmsiByVesselName.compute(normalized, (name, currentMmsi) -> {
			if (currentMmsi == null || currentMmsi.equals(position.mmsi())) {
				return position.mmsi();
			}
			AisPosition current = byMmsi.get(currentMmsi);
			if (current == null || !position.ingestedAt().isBefore(current.ingestedAt())) {
				return position.mmsi();
			}
			return currentMmsi;
		});
	}

	public void clear() {
		byMmsi.clear();
		mmsiByVesselName.clear();
	}

	static String normalize(String vesselName) {
		return vesselName.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
	}
}
