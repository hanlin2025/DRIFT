package com.drift.backend.shipment;

import java.util.List;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.exception.DuplicateShipmentReferenceException;
import com.drift.backend.shipment.exception.InvalidItineraryException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentCreationForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

@Service
public class ShipmentService {

	private static final String REFERENCE_UNIQUE_CONSTRAINT = "shipments_company_reference_key";

	private final ShipmentRepository shipments;
	private final UserAccountRepository users;
	private final ConnectionWindowService connectionWindows;
	private final LatestAisPositions latestPositions;

	public ShipmentService(ShipmentRepository shipments, UserAccountRepository users,
			ConnectionWindowService connectionWindows, LatestAisPositions latestPositions) {
		this.shipments = shipments;
		this.users = users;
		this.connectionWindows = connectionWindows;
		this.latestPositions = latestPositions;
	}

	@Transactional(readOnly = true)
	public List<ShipmentResponse> list(AuthenticatedUser principal) {
		Company company = activeCompany(principal);
		return shipments.findByCompanyIdOrderByCreatedAtDesc(company.getId()).stream()
				.map(this::respond)
				.toList();
	}

	@Transactional(readOnly = true)
	public ShipmentDetailResponse get(AuthenticatedUser principal, Long shipmentId) {
		return detail(visibleShipment(principal, shipmentId));
	}

	@Transactional(readOnly = true)
	public ShipmentTrackingResponse tracking(AuthenticatedUser principal, Long shipmentId) {
		Shipment shipment = visibleShipment(principal, shipmentId);
		return new ShipmentTrackingResponse(shipment.getMotherVessel(), livePosition(shipment.getMotherVessel()),
				shipment.getFeederVessel(), livePosition(shipment.getFeederVessel()));
	}

	@Transactional
	public ShipmentResponse create(AuthenticatedUser principal, CreateShipmentRequest request) {
		if (!request.plannedFeederDepartureAt().isAfter(request.plannedMotherArrivalAt())) {
			throw new InvalidItineraryException();
		}

		UserAccount creator = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = creator.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentCreationForbiddenException();
		}

		String shipmentReference = request.shipmentReference().strip();
		if (shipments.existsByCompanyIdAndShipmentReferenceIgnoreCase(company.getId(), shipmentReference)) {
			throw new DuplicateShipmentReferenceException();
		}

		Shipment shipment = new Shipment(company, creator, shipmentReference, request.origin().strip(),
				request.destination().strip(), request.transshipmentPort().strip(), request.motherVessel().strip(),
				request.plannedMotherArrivalAt(),
				request.feederVessel().strip(), request.plannedFeederDepartureAt());
		try {
			return respond(shipments.saveAndFlush(shipment));
		} catch (DataIntegrityViolationException ex) {
			if (isDuplicateReference(ex)) {
				throw new DuplicateShipmentReferenceException();
			}
			throw ex;
		}
	}

	private static boolean isDuplicateReference(DataIntegrityViolationException ex) {
		Throwable cause = ex.getCause();
		while (cause != null) {
			if (cause instanceof ConstraintViolationException violation
					&& REFERENCE_UNIQUE_CONSTRAINT.equals(violation.getConstraintName())) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	private ShipmentResponse respond(Shipment shipment) {
		return ShipmentResponse.from(shipment, window(shipment));
	}

	private Shipment visibleShipment(AuthenticatedUser principal, Long shipmentId) {
		Company company = activeCompany(principal);
		return shipments.findByIdAndCompanyId(shipmentId, company.getId())
				.orElseThrow(ShipmentNotFoundException::new);
	}

	private ShipmentDetailResponse detail(Shipment shipment) {
		return ShipmentDetailResponse.from(shipment, window(shipment),
				livePosition(shipment.getMotherVessel()), livePosition(shipment.getFeederVessel()));
	}

	private VesselPosition livePosition(String vesselName) {
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

	private ConnectionWindow window(Shipment shipment) {
		return connectionWindows.calculate(shipment.getPlannedMotherArrivalAt(),
				shipment.getPlannedFeederDepartureAt()).window();
	}

	private Company activeCompany(AuthenticatedUser principal) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = account.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentAccessForbiddenException();
		}
		return company;
	}
}
