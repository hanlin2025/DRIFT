package com.drift.backend.ais.position;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

@Service
public class LatestAisPositions {

	private final ConcurrentHashMap<String, AisPosition> byMmsi = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> mmsiByVesselName = new ConcurrentHashMap<>();

	public void record(AisPosition position) {
		AisPosition stored = byMmsi.merge(position.mmsi(), position, (current, incoming) ->
				incoming.ingestedAt().isBefore(current.ingestedAt()) ? current : incoming);
		if (stored == position && position.vesselName() != null) {
			mmsiByVesselName.put(normalize(position.vesselName()), position.mmsi());
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
		String mmsi = mmsiByVesselName.get(normalize(vesselName));
		return mmsi == null ? Optional.empty() : findByMmsi(mmsi);
	}

	static String normalize(String vesselName) {
		return vesselName.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
	}
}
