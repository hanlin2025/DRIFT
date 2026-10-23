package com.drift.backend.ais.position;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.shipment.ShipmentRiskAlertActivationStore;

@Service
public class LatestAisPositions {

	private final VesselObservationRepository observations;
	private final ShipmentRiskAlertActivationStore activation;
	private final ConcurrentHashMap<String, AisPosition> byMmsi = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> mmsiByVesselName = new ConcurrentHashMap<>();

	@Autowired
	public LatestAisPositions(VesselObservationRepository observations, ShipmentRiskAlertActivationStore activation) {
		this.observations = observations;
		this.activation = activation;
	}

	/** Unit-test constructor; Spring always supplies the activation gate. */
	public LatestAisPositions(VesselObservationRepository observations) {
		this.observations = observations;
		this.activation = null;
	}

	@Transactional
	public void record(AisPosition position) {
		if (activation != null) {
			activation.lockForRiskInput();
		}
		observations.save(new VesselObservation(position));
		AisPosition previous = byMmsi.get(position.mmsi());
		AisPosition stored = byMmsi.merge(position.mmsi(), position, (current, incoming) ->
				incoming.ingestedAt().isBefore(current.ingestedAt()) ? current : incoming);
		if (stored != position) {
			return;
		}
		if (previous != null && hasName(previous) && hasName(position)
				&& !normalize(previous.vesselName()).equals(normalize(position.vesselName()))) {
			mmsiByVesselName.remove(normalize(previous.vesselName()), position.mmsi());
		}
		if (hasName(position)) {
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
		Optional<AisPosition> inMemory = indexedPosition(normalized);
		Optional<AisPosition> stored = observations.findLatestByNormalizedVesselName(normalized)
				.map(VesselObservation::toAisPosition)
				.filter(position -> !memoryHasCurrentFix(position));
		if (inMemory.isEmpty()) {
			return stored;
		}
		if (stored.isEmpty() || !inMemory.get().ingestedAt().isBefore(stored.get().ingestedAt())) {
			return inMemory;
		}
		return stored;
	}

	/**
	 * Stored observations for one MMSI ingested after {@code from} and up to {@code to}, newest first,
	 * at most {@code limit} of them, each with its observation id. Reports flagged as invalid positions are left out.
	 */
	public List<StoredAisPosition> history(String mmsi, Instant from, Instant to, int limit) {
		if (mmsi == null || mmsi.isBlank()) {
			return List.of();
		}
		return observations.findNewestValidInWindow(mmsi.strip(), from, to, limit).stream()
				.map(VesselObservation::toStoredPosition)
				.toList();
	}

	private boolean memoryHasCurrentFix(AisPosition stored) {
		AisPosition current = byMmsi.get(stored.mmsi());
		return current != null && !current.ingestedAt().isBefore(stored.ingestedAt());
	}

	private Optional<AisPosition> indexedPosition(String normalized) {
		String mmsi = mmsiByVesselName.get(normalized);
		if (mmsi == null) {
			return Optional.empty();
		}
		AisPosition current = byMmsi.get(mmsi);
		if (current == null || (hasName(current) && !normalize(current.vesselName()).equals(normalized))) {
			return Optional.empty();
		}
		return Optional.of(current);
	}

	private void rememberNewestName(AisPosition position) {
		String normalized = normalize(position.vesselName());
		mmsiByVesselName.compute(normalized, (name, currentMmsi) -> {
			if (currentMmsi == null || currentMmsi.equals(position.mmsi())) {
				return position.mmsi();
			}
			AisPosition current = byMmsi.get(currentMmsi);
			if (current == null || !reportsName(current, normalized)
					|| !position.ingestedAt().isBefore(current.ingestedAt())) {
				return position.mmsi();
			}
			return currentMmsi;
		});
	}

	private static boolean hasName(AisPosition position) {
		return position.vesselName() != null && !position.vesselName().isBlank();
	}

	private static boolean reportsName(AisPosition position, String normalized) {
		return hasName(position) && normalize(position.vesselName()).equals(normalized);
	}

	public void clear() {
		byMmsi.clear();
		mmsiByVesselName.clear();
	}

	static String normalize(String vesselName) {
		return vesselName.strip().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
	}
}
