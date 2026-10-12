package com.drift.backend.shipment;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
import com.drift.backend.shipment.exception.InvalidShipmentRequestException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentCreationForbiddenException;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.drift.backend.shipment.exception.StaleShipmentVersionException;
import com.drift.backend.shipment.csvimport.ShipmentImportRow;

@Service
public class ShipmentService {

	private static final String REFERENCE_UNIQUE_CONSTRAINT = "shipments_company_reference_key";

	private final ShipmentRepository shipments;
	private final UserAccountRepository users;
	private final ConnectionWindowService connectionWindows;
	private final LatestAisPositions latestPositions;
	private final Validator validator;
	private final ShipmentAccessPolicy accessPolicy;

	public ShipmentService(ShipmentRepository shipments, UserAccountRepository users,
			ConnectionWindowService connectionWindows, LatestAisPositions latestPositions, Validator validator,
			ShipmentAccessPolicy accessPolicy) {
		this.shipments = shipments;
		this.users = users;
		this.connectionWindows = connectionWindows;
		this.latestPositions = latestPositions;
		this.validator = validator;
		this.accessPolicy = accessPolicy;
	}

	@Transactional(readOnly = true)
	public List<ShipmentResponse> list(AuthenticatedUser principal) {
		Company company = activeCompany(principal);
		return shipments.findVisibleByCompanyIdOrderByCreatedAtDesc(company.getId()).stream()
				.filter(shipment -> accessPolicy.canView(company, shipment))
				.map(this::respond)
				.toList();
	}

	@Transactional(readOnly = true)
	public ShipmentDetailResponse get(AuthenticatedUser principal, Long shipmentId) {
		Company company = activeCompany(principal);
		return shipments.findByIdVisibleToCompanyId(shipmentId, company.getId())
				.filter(shipment -> accessPolicy.canView(company, shipment))
				.map(this::detail)
				.orElseThrow(() -> shipments.existsById(shipmentId)
						? new ShipmentDetailForbiddenException()
						: new ShipmentNotFoundException());
	}

	@Transactional(readOnly = true)
	public ShipmentTrackingResponse tracking(AuthenticatedUser principal, Long shipmentId) {
		Shipment shipment = visibleShipment(principal, shipmentId);
		return new ShipmentTrackingResponse(shipment.getMotherVessel(), livePosition(shipment.getMotherVessel()),
				shipment.getFeederVessel(), livePosition(shipment.getFeederVessel()));
	}

	@Transactional
	public ShipmentResponse create(AuthenticatedUser principal, CreateShipmentRequest request) {
		ShipmentDetails details = validatedDetails(request);
		UserAccount creator = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = creator.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentCreationForbiddenException();
		}

		return respond(persistNewShipment(company, creator, details, null));
	}

	/** Reuses normal creation persistence for a row already validated by the CSV import pipeline. */
	@Transactional
	public void createImported(Company managingCompany, UserAccount creator, ShipmentImportRow row, Company importerCompany) {
		ShipmentDetails details = validatedDetails(new CreateShipmentRequest(row.shipmentReference(), row.origin(),
				row.destination(), row.transshipmentPort(), row.motherVessel(), row.plannedMotherArrivalAt(),
				row.feederVessel(), row.plannedFeederDepartureAt()));
		persistNewShipment(managingCompany, creator, details, importerCompany);
	}

	@Transactional
	public ShipmentResponse update(AuthenticatedUser principal, Long shipmentId, UpdateShipmentRequest request) {
		ShipmentDetails details = validatedDetails(request);
		Shipment shipment = editableShipment(principal, shipmentId);
		if (!request.version().equals(shipment.getVersion())) {
			throw new StaleShipmentVersionException();
		}

		Company company = activeCompany(principal);
		if (shipments.existsOtherByCompanyIdAndShipmentReferenceIgnoreCase(company.getId(), shipment.getId(),
				details.shipmentReference())) {
			throw new DuplicateShipmentReferenceException();
		}

		UserAccount editor = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		shipment.replaceDetails(details, editor, Instant.now());
		try {
			return respond(shipments.saveAndFlush(shipment));
		} catch (DataIntegrityViolationException ex) {
			if (isDuplicateReference(ex)) {
				throw new DuplicateShipmentReferenceException();
			}
			throw ex;
		} catch (OptimisticLockingFailureException ex) {
			throw new StaleShipmentVersionException();
		}
	}

	private ShipmentDetails validatedDetails(ShipmentDetailsRequest request) {
		Map<String, String> errors = new LinkedHashMap<>();
		for (ConstraintViolation<ShipmentDetailsRequest> violation : validator.validate(request)) {
			errors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
		}
		if (!errors.isEmpty()) {
			throw new InvalidShipmentRequestException(errors);
		}

		ShipmentDetails details = ShipmentDetails.from(request);
		if (!details.plannedFeederDepartureAt().isAfter(details.plannedMotherArrivalAt())) {
			throw new InvalidItineraryException();
		}
		return details;
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

	private Shipment persistNewShipment(Company company, UserAccount creator, ShipmentDetails details,
			Company importerCompany) {
		if (shipments.existsByCompanyIdAndShipmentReferenceIgnoreCase(company.getId(), details.shipmentReference())) {
			throw new DuplicateShipmentReferenceException();
		}

		Shipment shipment = new Shipment(company, creator, details.shipmentReference(), details.origin(),
				details.destination(), details.transshipmentPort(), details.motherVessel(), details.plannedMotherArrivalAt(),
				details.feederVessel(), details.plannedFeederDepartureAt());
		if (importerCompany != null) {
			shipment.linkImporter(importerCompany);
		}
		try {
			return shipments.saveAndFlush(shipment);
		} catch (DataIntegrityViolationException ex) {
			if (isDuplicateReference(ex)) {
				throw new DuplicateShipmentReferenceException();
			}
			throw ex;
		}
	}

	private ShipmentResponse respond(Shipment shipment) {
		return ShipmentResponse.from(shipment, window(shipment));
	}

	private Shipment visibleShipment(AuthenticatedUser principal, Long shipmentId) {
		Company company = activeCompany(principal);
		return shipments.findByIdVisibleToCompanyId(shipmentId, company.getId())
				.filter(shipment -> accessPolicy.canView(company, shipment))
				.orElseThrow(ShipmentNotFoundException::new);
	}

	private Shipment editableShipment(AuthenticatedUser principal, Long shipmentId) {
		Company company = activeCompany(principal);
		Shipment shipment = shipments.findByIdAndCompanyId(shipmentId, company.getId()).orElse(null);
		if (shipment != null && accessPolicy.canEdit(company, shipment)) {
			return shipment;
		}
		if (shipments.findByIdVisibleToCompanyId(shipmentId, company.getId()).isPresent()) {
			throw new ShipmentDetailForbiddenException();
		}
		throw new ShipmentNotFoundException();
	}

	private ShipmentDetailResponse detail(Shipment shipment) {
		return ShipmentDetailResponse.from(shipment, calculation(shipment),
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
		return calculation(shipment).window();
	}

	private ConnectionWindowCalculation calculation(Shipment shipment) {
		return connectionWindows.calculate(shipment.getPlannedMotherArrivalAt(),
				shipment.getPlannedFeederDepartureAt());
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
