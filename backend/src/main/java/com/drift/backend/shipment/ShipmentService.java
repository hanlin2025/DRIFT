package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.exception.DuplicateShipmentReferenceException;
import com.drift.backend.shipment.exception.InvalidItineraryException;
import com.drift.backend.shipment.exception.InvalidShipmentRequestException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentAlreadyActiveException;
import com.drift.backend.shipment.exception.ShipmentAlreadyArchivedException;
import com.drift.backend.shipment.exception.ShipmentArchiveForbiddenException;
import com.drift.backend.shipment.exception.ShipmentRestoreForbiddenException;
import com.drift.backend.shipment.exception.ShipmentCreationForbiddenException;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotEditableException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;
import com.drift.backend.shipment.exception.StaleShipmentVersionException;
import com.drift.backend.shipment.csvimport.ShipmentImportRow;
import com.drift.backend.shipment.history.ShipmentHistoryRecorder;
import com.drift.backend.shipment.history.ShipmentSnapshot;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ShipmentService {

	public static final String REMOVED_MESSAGE = "Shipment removed successfully";
	public static final String RESTORED_MESSAGE = "Shipment restored successfully";

	public static final int PAGE_SIZE = 25;

	private static final String REFERENCE_UNIQUE_CONSTRAINT = "shipments_company_reference_key";

	private final ShipmentRepository shipments;
	private final ShipmentRiskStateRepository riskStates;
	private final UserAccountRepository users;
	private final ShipmentConnectionRisk connectionRisk;
	private final Validator validator;
	private final ShipmentAccessPolicy accessPolicy;
	private final ObjectMapper objectMapper;
	private final ShipmentHistoryRecorder history;
	private final Clock clock;
	private final ApplicationEventPublisher events;
	private final ShipmentRiskAlertActivationStore activation;
	private final ShipmentDetailAssembler details;

	public ShipmentService(ShipmentRepository shipments, ShipmentRiskStateRepository riskStates, UserAccountRepository users,
			ShipmentConnectionRisk connectionRisk, Validator validator,
			ShipmentAccessPolicy accessPolicy, ObjectMapper objectMapper, ObjectProvider<Clock> clocks,
			ApplicationEventPublisher events, ShipmentRiskAlertActivationStore activation,
			ShipmentHistoryRecorder history, ShipmentDetailAssembler details) {
		this.shipments = shipments;
		this.riskStates = riskStates;
		this.users = users;
		this.connectionRisk = connectionRisk;
		this.validator = validator;
		this.accessPolicy = accessPolicy;
		this.objectMapper = objectMapper;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
		this.events = events;
		this.activation = activation;
		this.history = history;
		this.details = details;
	}

	@Transactional(readOnly = true)
	public List<ShipmentResponse> list(AuthenticatedUser principal) {
		return list(principal, ShipmentFilterCriteria.none());
	}

	@Transactional(readOnly = true)
	public List<ShipmentResponse> list(AuthenticatedUser principal, ShipmentFilterCriteria criteria) {
		return list(principal, criteria, ShipmentSort.CREATED_DATE_DESCENDING);
	}

	@Transactional(readOnly = true)
	public List<ShipmentResponse> list(AuthenticatedUser principal, ShipmentFilterCriteria criteria, ShipmentSort sort) {
		if (criteria == null || sort == null) {
			throw new IllegalArgumentException("Filter criteria and sort are required.");
		}
		return ranked(activeCompany(principal), criteria, sort);
	}

	@Transactional(readOnly = true)
	public ShipmentPage listPage(AuthenticatedUser principal, ShipmentFilterCriteria criteria, int page,
			ShipmentSort sort) {
		if (page < 1) {
			throw new IllegalArgumentException("Page must be at least 1.");
		}
		if (criteria == null || sort == null) {
			throw new IllegalArgumentException("Filter criteria and sort are required.");
		}
		Company company = activeCompany(principal);
		if (sort.computed() || criteria.atRisk()) {
			List<ShipmentResponse> all = ranked(company, criteria, sort);
			int from = Math.min(all.size(), (page - 1) * PAGE_SIZE);
			return new ShipmentPage(all.subList(from, Math.min(all.size(), from + PAGE_SIZE)), page, PAGE_SIZE,
					all.size());
		}
		Page<Shipment> result = shipments.findAll(ShipmentSpecifications.matching(company.getId(), criteria),
				PageRequest.of(page - 1, PAGE_SIZE, sort.toSort()));
		return new ShipmentPage(respondAll(result.getContent()), page, PAGE_SIZE,
				result.getTotalElements());
	}

	/**
	 * Every authorised shipment matching the filters, in the requested order. Risk depends on live AIS and is not
	 * stored, so the at-risk filter and the risk sort run on the mapped rows after the authorised,
	 * filtered query, and a page is cut only after ranking. That keeps paging correct at the cost of reading the
	 * caller's whole filtered portfolio, whose AIS positions come from one batched lookup.
	 */
	private List<ShipmentResponse> ranked(Company company, ShipmentFilterCriteria criteria, ShipmentSort sort) {
		Sort order = (sort.computed() ? ShipmentSort.CREATED_DATE_DESCENDING : sort).toSort();
		List<ShipmentResponse> rows = respondAll(shipments.findAll(
				ShipmentSpecifications.matching(company.getId(), criteria), order));
		if (criteria.atRisk()) {
			rows = rows.stream().filter(row -> ShipmentFilterCriteria.isAtRisk(row.risk())).toList();
		}
		if (sort.computed()) {
			rows = rows.stream().sorted(sort.comparator()).toList();
		}
		return rows;
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
		VesselPosition motherVessel = connectionRisk.position(shipment.getMotherVessel());
		VesselPosition feederVessel = connectionRisk.position(shipment.getFeederVessel());
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

		Shipment shipment = persistNewShipment(company, creator, details, null);
		events.publishEvent(new ShipmentRiskEvaluationRequestedEvent(shipment.getId()));
		return respond(shipment);
	}

	/** Reuses normal creation persistence for a row already validated by the CSV import pipeline. */
	@Transactional
	public void createImported(Company managingCompany, UserAccount creator, ShipmentImportRow row, Company importerCompany) {
		ShipmentDetails details = validatedDetails(new CreateShipmentRequest(row.shipmentReference(), row.origin(),
				row.destination(), row.transshipmentPort(), row.motherVessel(), row.plannedMotherArrivalAt(),
				row.feederVessel(), row.plannedFeederDepartureAt(), null, null, null, null, null, null));
		Shipment shipment = persistNewShipment(managingCompany, creator, details, importerCompany);
		events.publishEvent(new ShipmentRiskEvaluationRequestedEvent(shipment.getId()));
	}

	@Transactional
	public void archive(AuthenticatedUser principal, Long shipmentId) {
		activation.lockForRiskInput();
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
		ShipmentStatus previousStatus = shipment.getStatus();
		Instant now = Instant.now();
		shipment.archive();
		shipment.markUpdated(account, now);
		shipments.saveAndFlush(shipment);
		history.recordStatusChange(shipment, account, previousStatus, now);
	}

	@Transactional
	public void restore(AuthenticatedUser principal, Long shipmentId) {
		activation.lockForRiskInput();
		UserAccount account = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = account.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentAccessForbiddenException();
		}
		Shipment shipment = shipments.findByIdAndCompanyId(shipmentId, company.getId())
				.orElseThrow(ShipmentNotFoundException::new);
		if (account.getRole() != Role.ADMIN && account.getRole() != Role.LOGISTICS_MANAGER) {
			throw new ShipmentRestoreForbiddenException();
		}
		if (shipment.getStatus() == ShipmentStatus.ACTIVE) {
			throw new ShipmentAlreadyActiveException();
		}
		ShipmentStatus previousStatus = shipment.getStatus();
		Instant now = Instant.now();
		shipment.restore();
		shipment.markUpdated(account, now);
		shipments.saveAndFlush(shipment);
		history.recordStatusChange(shipment, account, previousStatus, now);
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
		activation.lockForRiskInput();
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
		boolean scheduleChanged = !shipment.getPlannedMotherArrivalAt().equals(details.plannedMotherArrivalAt())
				|| !shipment.getPlannedFeederDepartureAt().equals(details.plannedFeederDepartureAt());
		ShipmentSnapshot before = ShipmentSnapshot.of(shipment);
		Instant now = Instant.now();
		shipment.replaceDetails(details, editor, now);
		shipment.applySpecifiedIdentity(details, identity);
		Shipment saved;
		try {
			saved = shipments.saveAndFlush(shipment);
		} catch (DataIntegrityViolationException ex) {
			if (isDuplicateReference(ex)) {
				throw new DuplicateShipmentReferenceException();
			}
			throw ex;
		} catch (OptimisticLockingFailureException ex) {
			throw new StaleShipmentVersionException();
		}
		history.recordUpdate(before, saved, editor, now);
		if (scheduleChanged) {
			events.publishEvent(new ShipmentRiskEvaluationRequestedEvent(saved.getId()));
		}
		return respond(saved);
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
		ShipmentRiskAlertActivation activeGeneration = activation.lockForRiskInput();
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
		Shipment saved;
		try {
			saved = shipments.saveAndFlush(shipment);
			riskStates.saveAndFlush(new ShipmentRiskState(saved,
					ShipmentRiskBaselineMode.ALERTABLE_FIRST_VALID, activeGeneration.getGeneration(), Instant.now(clock)));
		} catch (DataIntegrityViolationException ex) {
			if (isDuplicateReference(ex)) {
				throw new DuplicateShipmentReferenceException();
			}
			throw ex;
		}
		history.recordCreation(saved, creator);
		return saved;
	}

	private ShipmentResponse respond(Shipment shipment) {
		return ShipmentResponse.from(shipment, connectionRisk.assess(shipment));
	}

	/** Builds list rows with one batched AIS lookup, so each row's risk matches its detail page without a query per row. */
	private List<ShipmentResponse> respondAll(List<Shipment> listed) {
		List<ShipmentConnectionRisk.Snapshot> connections = connectionRisk.assessAll(listed);
		List<ShipmentResponse> responses = new ArrayList<>(listed.size());
		for (int i = 0; i < listed.size(); i++) {
			responses.add(ShipmentResponse.from(listed.get(i), connections.get(i)));
		}
		return responses;
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
		return details.detail(shipment);
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
