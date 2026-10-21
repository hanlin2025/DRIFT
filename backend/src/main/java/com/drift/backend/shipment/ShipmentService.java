package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
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
import com.drift.backend.shipment.exception.ShipmentAlreadyArchivedException;
import com.drift.backend.shipment.exception.ShipmentArchiveForbiddenException;
import com.drift.backend.shipment.exception.ShipmentCreationForbiddenException;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotEditableException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.drift.backend.shipment.exception.StaleShipmentVersionException;
import com.drift.backend.shipment.csvimport.ShipmentImportRow;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ShipmentService {

	public static final String REMOVED_MESSAGE = "Shipment removed successfully";

	private static final String REFERENCE_UNIQUE_CONSTRAINT = "shipments_company_reference_key";

	private final ShipmentRepository shipments;
	private final ShipmentRiskStateRepository riskStates;
	private final UserAccountRepository users;
	private final ConnectionWindowService connectionWindows;
	private final LatestAisPositions latestPositions;
	private final Validator validator;
	private final ShipmentAccessPolicy accessPolicy;
	private final ObjectMapper objectMapper;
	private final ArrivalEstimateCalculator arrivalEstimates;
	private final Clock clock;

	public ShipmentService(ShipmentRepository shipments, ShipmentRiskStateRepository riskStates, UserAccountRepository users,
			ConnectionWindowService connectionWindows, LatestAisPositions latestPositions, Validator validator,
			ShipmentAccessPolicy accessPolicy, ObjectMapper objectMapper, ArrivalEstimateCalculator arrivalEstimates,
			ObjectProvider<Clock> clocks) {
		this.shipments = shipments;
		this.riskStates = riskStates;
		this.users = users;
		this.connectionWindows = connectionWindows;
		this.latestPositions = latestPositions;
		this.validator = validator;
		this.accessPolicy = accessPolicy;
		this.objectMapper = objectMapper;
		this.arrivalEstimates = arrivalEstimates;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	@Transactional(readOnly = true)
	public List<ShipmentResponse> list(AuthenticatedUser principal) {
		return list(principal, ShipmentFilterCriteria.none());
	}

	@Transactional(readOnly = true)
	public List<ShipmentResponse> list(AuthenticatedUser principal, ShipmentFilterCriteria criteria) {
		Company company = activeCompany(principal);
		return shipments.findAll(ShipmentSpecifications.matching(company.getId(), criteria),
				Sort.by(Sort.Direction.DESC, "createdAt")).stream()
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
		VesselPosition motherVessel = livePosition(shipment.getMotherVessel());
		VesselPosition feederVessel = livePosition(shipment.getFeederVessel());
		return new ShipmentTrackingResponse(shipment.getMotherVessel(), motherVessel,
				VesselNames.warning(shipment.getMotherVessel(), motherVessel, VesselNames.MOTHER_MISMATCH),
				shipment.getFeederVessel(), feederVessel,
				VesselNames.warning(shipment.getFeederVessel(), feederVessel, VesselNames.FEEDER_MISMATCH));
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
				row.feederVessel(), row.plannedFeederDepartureAt(), null, null, null, null, null, null));
		persistNewShipment(managingCompany, creator, details, importerCompany);
	}

	@Transactional
	public void archive(AuthenticatedUser principal, Long shipmentId) {
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = account.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentAccessForbiddenException();
		}
		Shipment shipment = shipments.findByIdAndCompanyId(shipmentId, company.getId())
				.orElseThrow(ShipmentNotFoundException::new);
		if (account.getRole() != Role.ADMIN && account.getRole() != Role.LOGISTICS_MANAGER) {
			throw new ShipmentArchiveForbiddenException();
		}
		if (shipment.getStatus() == ShipmentStatus.ARCHIVED) {
			throw new ShipmentAlreadyArchivedException();
		}
		shipment.archive();
		shipment.markUpdated(account, Instant.now());
		shipments.saveAndFlush(shipment);
	}

	@Transactional
	public ShipmentResponse update(AuthenticatedUser principal, Long shipmentId, JsonNode body) {
		if (body == null || !body.isObject()) {
			throw new InvalidShipmentRequestException(Map.of());
		}
		UpdateShipmentRequest request;
		try {
			request = objectMapper.convertValue(body, UpdateShipmentRequest.class);
		} catch (IllegalArgumentException | JacksonException ex) {
			throw new InvalidShipmentRequestException(Map.of());
		}
		return update(principal, shipmentId, request, VesselIdentityPresence.from(body));
	}

	@Transactional
	public ShipmentResponse update(AuthenticatedUser principal, Long shipmentId, UpdateShipmentRequest request) {
		return update(principal, shipmentId, request, VesselIdentityPresence.omitted());
	}

	private ShipmentResponse update(AuthenticatedUser principal, Long shipmentId, UpdateShipmentRequest request,
			VesselIdentityPresence identity) {
		ShipmentDetails details = validatedDetails(request);
		Shipment shipment = editableShipment(principal, shipmentId);
		if (shipment.getStatus() == ShipmentStatus.ARCHIVED) {
			throw new ShipmentNotEditableException();
		}
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
		shipment.applySpecifiedIdentity(details, identity);
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
		checkDigits(request.motherVesselImo(), "motherVesselImo", "Mother vessel IMO must be 7 digits.", 7, errors);
		checkDigits(request.motherVesselMmsi(), "motherVesselMmsi", "Mother vessel MMSI must be 9 digits.", 9, errors);
		checkDigits(request.feederVesselImo(), "feederVesselImo", "Feeder vessel IMO must be 7 digits.", 7, errors);
		checkDigits(request.feederVesselMmsi(), "feederVesselMmsi", "Feeder vessel MMSI must be 9 digits.", 9, errors);
		if (!errors.isEmpty()) {
			throw new InvalidShipmentRequestException(errors);
		}

		ShipmentDetails details = ShipmentDetails.from(request);
		if (!details.plannedFeederDepartureAt().isAfter(details.plannedMotherArrivalAt())) {
			throw new InvalidItineraryException();
		}
		return details;
	}

	private static void checkDigits(String raw, String field, String message, int length, Map<String, String> errors) {
		if (raw == null || raw.isBlank()) {
			return;
		}
		if (!raw.strip().matches("\\d{" + length + "}")) {
			errors.putIfAbsent(field, message);
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

	private Shipment persistNewShipment(Company company, UserAccount creator, ShipmentDetails details,
			Company importerCompany) {
		if (shipments.existsByCompanyIdAndShipmentReferenceIgnoreCase(company.getId(), details.shipmentReference())) {
			throw new DuplicateShipmentReferenceException();
		}

		Shipment shipment = new Shipment(company, creator, details.shipmentReference(), details.origin(),
				details.destination(), details.transshipmentPort(), details.motherVessel(), details.plannedMotherArrivalAt(),
				details.feederVessel(), details.plannedFeederDepartureAt());
		shipment.recordVesselIdentity(details);
		if (importerCompany != null) {
			shipment.linkImporter(importerCompany);
		}
		try {
			Shipment saved = shipments.saveAndFlush(shipment);
			riskStates.saveAndFlush(new ShipmentRiskState(saved,
					ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID, Instant.now(clock)));
			return saved;
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
		VesselPosition motherVesselPosition = livePosition(shipment.getMotherVessel());
		return ShipmentDetailResponse.from(shipment, calculation(shipment), motherVesselPosition,
				livePosition(shipment.getFeederVessel()),
				arrivalEstimates.estimate(ArrivalFix.from(motherVesselPosition), shipment.getTransshipmentPort(),
						shipment.getPlannedMotherArrivalAt(), clock));
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
