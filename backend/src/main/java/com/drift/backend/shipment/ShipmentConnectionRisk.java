package com.drift.backend.shipment;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.maritimechange.CurrentMaritimeSignals;
import com.drift.backend.maritimechange.MaritimeSignal;
import com.drift.backend.maritimechange.ShipmentMatchFacts;

/**
 * Connection-window risk for one shipment. Shipment detail and risk evaluation both use this,
 * so a usable arrival estimate and a planned-window fallback stay the same result.
 * Current MPA port data and NEA weather attribute the shipment's behaviour and can raise the risk one band.
 */
@Service
class ShipmentConnectionRisk {

	private final ConnectionWindowService connectionWindows;
	private final LatestAisPositions latestPositions;
	private final ArrivalEstimateCalculator arrivalEstimates;
	private final CurrentMaritimeSignals maritimeSignals;
	private final ShipmentDelayAttributionProperties attributionThresholds;
	private final Clock clock;

	ShipmentConnectionRisk(ConnectionWindowService connectionWindows, LatestAisPositions latestPositions,
			ArrivalEstimateCalculator arrivalEstimates, CurrentMaritimeSignals maritimeSignals,
			ShipmentDelayAttributionProperties attributionThresholds, ObjectProvider<Clock> clocks) {
		this.connectionWindows = connectionWindows;
		this.latestPositions = latestPositions;
		this.arrivalEstimates = arrivalEstimates;
		this.maritimeSignals = maritimeSignals;
		this.attributionThresholds = attributionThresholds;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	Snapshot assess(Shipment shipment) {
		return assess(shipment, this::position, signals(List.of(shipment)));
	}

	/**
	 * Assesses many shipments with one batched AIS lookup instead of one lookup per shipment, so the shipment list
	 * gets the same risk as the detail page without a query per row.
	 */
	List<Snapshot> assessAll(List<Shipment> shipments) {
		List<String> names = new ArrayList<>();
		for (Shipment shipment : shipments) {
			addLookupNames(names, shipment.getMotherVessel());
			addLookupNames(names, shipment.getFeederVessel());
		}
		Map<String, AisPosition> found = latestPositions.findByVesselNames(names);
		Function<String, VesselPosition> positions = vesselName -> Optional.ofNullable(vesselName)
				.map(found::get)
				.or(() -> Optional.ofNullable(withoutVesselPrefix(vesselName)).map(found::get))
				.map(VesselPosition::from)
				.orElse(null);
		Map<Long, List<MaritimeSignal>> signals = signals(shipments);
		return shipments.stream().map(shipment -> assess(shipment, positions, signals)).toList();
	}

	/** One read of the current maritime signals for every shipment, so a list does not query per row. */
	private Map<Long, List<MaritimeSignal>> signals(List<Shipment> shipments) {
		List<ShipmentMatchFacts> facts = shipments.stream()
				.filter(shipment -> shipment.getId() != null && shipment.getCompany() != null
						&& shipment.getCompany().getId() != null)
				.map(shipment -> new ShipmentMatchFacts(shipment.getId(), shipment.getCompany().getId(),
						shipment.getTransshipmentPort(), shipment.getMotherVesselImo(), shipment.getFeederVesselImo(),
						shipment.getPlannedMotherArrivalAt(), shipment.getPlannedFeederDepartureAt()))
				.toList();
		return maritimeSignals.forShipments(facts);
	}

	private Snapshot assess(Shipment shipment, Function<String, VesselPosition> positions,
			Map<Long, List<MaritimeSignal>> signals) {
		VesselPosition motherVesselPosition = positions.apply(shipment.getMotherVessel());
		ArrivalEstimate arrivalComparison = shipment.getPlannedMotherArrivalAt() == null ? null
				: arrivalEstimates.estimate(ArrivalFix.from(motherVesselPosition),
						shipment.getTransshipmentPort(), shipment.getPlannedMotherArrivalAt(), clock);
		ConnectionWindowCalculation planned = connectionWindows.calculate(shipment.getPlannedMotherArrivalAt(),
				shipment.getPlannedFeederDepartureAt());
		RemainingConnectionWindow remaining = connectionWindows.remaining(shipment.getPlannedFeederDepartureAt(),
				arrivalComparison);
		ShipmentRisk baseRisk = ShipmentRisk.of(planned, remaining);
		ShipmentDelayAttribution attribution = ShipmentDelayAttribution.of(arrivalComparison,
				shipment.getId() == null ? List.of() : signals.getOrDefault(shipment.getId(), List.of()),
				shipment.getPlannedMotherArrivalAt(), shipment.getPlannedFeederDepartureAt(), attributionThresholds);
		ShipmentRisk risk = ShipmentRisk.adjusted(baseRisk, attribution);
		if (risk != null && baseRisk != null && risk.level() != baseRisk.level()) {
			attribution = attribution.raisedFrom(baseRisk.level());
		}
		return new Snapshot(planned, remaining, motherVesselPosition, positions.apply(shipment.getFeederVessel()),
				arrivalComparison, risk, baseRisk, attribution);
	}

	private static void addLookupNames(List<String> names, String vesselName) {
		if (vesselName != null) {
			names.add(vesselName);
			names.add(withoutVesselPrefix(vesselName));
		}
	}

	VesselPosition position(String vesselName) {
		return latestPositions.findByVesselName(vesselName)
				.or(() -> latestPositions.findByVesselName(withoutVesselPrefix(vesselName)))
				.map(VesselPosition::from)
				.orElse(null);
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

	record Snapshot(
			ConnectionWindowCalculation planned,
			RemainingConnectionWindow remaining,
			VesselPosition motherVesselPosition,
			VesselPosition feederVesselPosition,
			ArrivalEstimate arrivalComparison,
			ShipmentRisk risk,
			ShipmentRisk baseRisk,
			ShipmentDelayAttribution attribution) {

		/** A snapshot without external signals: the risk is the window risk and nothing is attributed. */
		Snapshot(ConnectionWindowCalculation planned, RemainingConnectionWindow remaining,
				VesselPosition motherVesselPosition, VesselPosition feederVesselPosition,
				ArrivalEstimate arrivalComparison, ShipmentRisk risk) {
			this(planned, remaining, motherVesselPosition, feederVesselPosition, arrivalComparison, risk, risk,
					ShipmentDelayAttribution.unavailable());
		}
	}
}
