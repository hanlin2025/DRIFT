package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.AccountStatus;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.ais.position.AisPosition;
import com.drift.backend.company.Company;
import com.drift.backend.maritimecontext.MaritimeContextResponse;
import com.drift.backend.maritimecontext.MaritimeContextService;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.AisObservation;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.Scenario;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.ShipmentInput;
import com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.Stage;
import com.drift.backend.shipment.ControlledScenarioSessionResponse.ShipmentBinding;
import com.drift.backend.shipment.exception.ControlledScenarioAccessDeniedException;
import com.drift.backend.shipment.exception.ControlledScenarioInvalidRequestException;
import com.drift.backend.shipment.exception.ControlledScenarioUnavailableException;
import com.drift.backend.shipment.exception.ScenarioSessionNotFoundException;
import com.drift.backend.shipment.exception.ScenarioTransitionConflictException;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

/** Manually controlled, bounded, process-local scenario sessions backed only by immutable fixture data. */
@Service
@Transactional
public class ControlledScenarioSessionService {
	private static final String API_VERSION = "1";
	private static final int SHIPMENT_A_INDEX = 0;
	private static final int SHIPMENT_B_INDEX = 1;

	private final Scenario scenario;
	private final UserAccountRepository users;
	private final ShipmentService shipmentService;
	private final ShipmentRepository shipmentRepository;
	private final ShipmentConnectionRisk connectionRisk;
	private final MaritimeContextService maritimeContext;
	private final ControlledScenarioProperties properties;
	private final Clock clock;
	private final ControlledScenarioEmailProperties emailProperties;
	private final ApplicationEventPublisher events;
	private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();

	@Autowired
	public ControlledScenarioSessionService(UserAccountRepository users, ShipmentService shipmentService,
			ShipmentRepository shipmentRepository, ShipmentConnectionRisk connectionRisk,
			MaritimeContextService maritimeContext, ControlledScenarioProperties properties,
			ControlledScenarioEmailProperties emailProperties, ApplicationEventPublisher events,
			ObjectProvider<Clock> clocks) {
		this(users, shipmentService, shipmentRepository, connectionRisk, maritimeContext, properties,
				clocks.getIfAvailable(Clock::systemUTC), SyntheticDisruptionScenarioFixture.create("Api"), emailProperties, events);
	}

	ControlledScenarioSessionService(UserAccountRepository users, ShipmentService shipmentService,
			ShipmentRepository shipmentRepository, ShipmentConnectionRisk connectionRisk,
			MaritimeContextService maritimeContext, ControlledScenarioProperties properties, Clock clock,
			Scenario scenario) {
		this(users, shipmentService, shipmentRepository, connectionRisk, maritimeContext, properties, clock, scenario,
				new ControlledScenarioEmailProperties(), event -> { });
	}

	ControlledScenarioSessionService(UserAccountRepository users, ShipmentService shipmentService,
			ShipmentRepository shipmentRepository, ShipmentConnectionRisk connectionRisk,
			MaritimeContextService maritimeContext, ControlledScenarioProperties properties, Clock clock,
			Scenario scenario, ControlledScenarioEmailProperties emailProperties, ApplicationEventPublisher events) {
		if (scenario == null) throw new IllegalStateException("The controlled scenario fixture is unavailable.");
		this.scenario = scenario.validate();
		if (this.scenario.shipments().size() != 2 || this.scenario.stages().size() != 3) {
			throw new IllegalStateException("The controlled scenario requires exactly two shipments and three stages.");
		}
		this.users = Objects.requireNonNull(users);
		this.shipmentService = Objects.requireNonNull(shipmentService);
		this.shipmentRepository = Objects.requireNonNull(shipmentRepository);
		this.connectionRisk = Objects.requireNonNull(connectionRisk);
		this.maritimeContext = Objects.requireNonNull(maritimeContext);
		this.properties = Objects.requireNonNull(properties);
		this.clock = Objects.requireNonNull(clock);
		this.emailProperties = Objects.requireNonNull(emailProperties);
		this.events = Objects.requireNonNull(events);
	}

	/** Creates an unstarted session anchored to one configured portfolio shipment. */
	public ControlledScenarioSessionResponse create(AuthenticatedUser principal, UUID sessionId, Long anchorShipmentId) {
		Owner owner = owner(principal);
		List<ShipmentBinding> bindings = authorizedBindings(principal, owner);
		if (bindings.stream().noneMatch(binding -> binding.shipmentId().equals(anchorShipmentId))) {
			throw new ScenarioSessionNotFoundException();
		}
		Instant now = clock.instant();
		synchronized (sessions) {
			purgeExpired(now);
			Session existing = sessions.get(sessionId);
			if (existing != null) {
				synchronized (existing) {
					assertOwner(existing, owner);
					if (!existing.anchorShipmentId.equals(anchorShipmentId)) {
						throw conflict("SESSION_ID_REUSED", "This session ID was already created for another shipment.");
					}
					existing.lastAccessedAt = now;
					return response(sessionId, existing);
				}
			}
			if (sessions.size() >= properties.getMaxSessions()) {
				throw unavailable("SESSION_CAPACITY", HttpStatus.SERVICE_UNAVAILABLE.value(),
						"The controlled scenario session limit has been reached.");
			}
			Session created = new Session(owner.userId(), owner.companyId(), anchorShipmentId, bindings, now);
			sessions.put(sessionId, created);
			return response(sessionId, created);
		}
	}

	public ControlledScenarioSessionResponse get(AuthenticatedUser principal, UUID sessionId) {
		Session session = ownedSession(principal, sessionId);
		synchronized (session) {
			return response(sessionId, session);
		}
	}

	public ControlledScenarioSessionResponse start(AuthenticatedUser principal, UUID sessionId,
			ControlledScenarioCommandRequest request, String commandKey) {
		return command(principal, sessionId, request, commandKey, "START", session -> {
			if (session.state != ControlledScenarioSessionState.UNSTARTED) return response(sessionId, session);
			int baselineOrder = 0;
			long revision = session.revision + 1;
			ControlledScenarioSnapshot snapshot = createSnapshot(session, baselineOrder, revision);
			session.stageOrder = baselineOrder;
			session.revision = revision;
			session.state = ControlledScenarioSessionState.ACTIVE;
			session.snapshot = snapshot;
			return response(sessionId, session);
		});
	}

	public ControlledScenarioSessionResponse next(AuthenticatedUser principal, UUID sessionId,
			ControlledScenarioCommandRequest request, String commandKey) {
		AtomicReference<ControlledScenarioWorsenedEvent> triggered = new AtomicReference<>();
		ControlledScenarioSessionResponse response = command(principal, sessionId, request, commandKey, "NEXT", session -> {
			if (session.state == ControlledScenarioSessionState.UNSTARTED) {
				throw conflict("SCENARIO_NOT_STARTED", "Start the scenario before advancing it.");
			}
			if (session.state == ControlledScenarioSessionState.COMPLETE) {
				throw conflict("SCENARIO_COMPLETE", "The scenario is already at WORSENED.");
			}
			int nextOrder = session.stageOrder + 1;
			long revision = session.revision + 1;
			ControlledScenarioSnapshot snapshot = createSnapshot(session, nextOrder, revision);
			session.stageOrder = nextOrder;
			session.revision = revision;
			session.state = nextOrder == scenario.stages().size() - 1
					? ControlledScenarioSessionState.COMPLETE : ControlledScenarioSessionState.ACTIVE;
			session.snapshot = snapshot;
			if (nextOrder == 2) {
				ControlledScenarioEmailStatus status = !emailProperties.isEnabled()
						? ControlledScenarioEmailStatus.DISABLED
						: emailProperties.approvedRecipients().isEmpty()
								? ControlledScenarioEmailStatus.NO_APPROVED_RECIPIENTS
								: ControlledScenarioEmailStatus.SUBMISSION_PENDING;
				session.emailSubmissions.put(session.epochId, new EmailSubmission(revision, status));
				if (status == ControlledScenarioEmailStatus.SUBMISSION_PENDING) {
					String scenarioShipmentKey = session.shipments.stream()
							.filter(binding -> binding.shipmentId().equals(session.anchorShipmentId))
							.map(ShipmentBinding::scenarioShipmentKey).findFirst().orElseThrow();
					triggered.set(new ControlledScenarioWorsenedEvent(sessionId, session.epochId, revision,
							scenarioShipmentKey, snapshot));
				}
			}
			return response(sessionId, session);
		});
		ControlledScenarioWorsenedEvent pending = triggered.get();
		if (pending != null) events.publishEvent(pending);
		return response;
	}

	boolean claimEmailSubmission(UUID sessionId, UUID epochId, long revision) {
		Session session = sessions.get(sessionId);
		if (session == null) return false;
		synchronized (session) {
			EmailSubmission current = session.emailSubmissions.get(epochId);
			if (current == null || current.revision() != revision
					|| current.status() != ControlledScenarioEmailStatus.SUBMISSION_PENDING) return false;
			session.emailSubmissions.put(epochId, new EmailSubmission(revision, ControlledScenarioEmailStatus.SUBMITTING));
			return true;
		}
	}

	void recordEmailSubmissionOutcome(UUID sessionId, UUID epochId, long revision, ControlledScenarioEmailStatus status) {
		Session session = sessions.get(sessionId);
		if (session == null) return;
		synchronized (session) {
			EmailSubmission current = session.emailSubmissions.get(epochId);
			if (current != null && current.revision() == revision && current.status() == ControlledScenarioEmailStatus.SUBMITTING) {
				session.emailSubmissions.put(epochId, new EmailSubmission(revision, status));
			}
		}
	}

	public ControlledScenarioSessionResponse reset(AuthenticatedUser principal, UUID sessionId,
			ControlledScenarioCommandRequest request, String commandKey) {
		return command(principal, sessionId, request, commandKey, "RESET", session -> {
			if (session.state != ControlledScenarioSessionState.UNSTARTED) {
				session.epochId = UUID.randomUUID();
				session.revision = 0;
				session.stageOrder = -1;
				session.state = ControlledScenarioSessionState.UNSTARTED;
				session.snapshot = null;
			}
			return response(sessionId, session);
		});
	}

	private ControlledScenarioSessionResponse command(AuthenticatedUser principal, UUID sessionId,
			ControlledScenarioCommandRequest request, String commandKey, String commandType,
			java.util.function.Function<Session, ControlledScenarioSessionResponse> action) {
		Session session = ownedSession(principal, sessionId);
		validateCommandKey(commandKey);
		String fingerprint = commandType + ":" + request.expectedEpochId() + ":" + request.expectedRevision();
		synchronized (session) {
			CommandOutcome previous = session.commandResults.get(commandKey);
			if (previous != null) {
				if (!previous.fingerprint().equals(fingerprint)) {
					throw conflict("IDEMPOTENCY_KEY_REUSED", "This command key was already used with different parameters.");
				}
				if (previous.conflictCode() != null) {
					throw conflict(previous.conflictCode(), previous.conflictMessage());
				}
				return previous.response();
			}
			if (session.commandResults.size() >= properties.getMaxCommandsPerSession()) {
				throw unavailable("SESSION_COMMAND_LIMIT", HttpStatus.TOO_MANY_REQUESTS.value(),
						"The command limit for this session has been reached. Create a new scenario session.");
			}
			try {
				assertCurrent(session, request);
				ControlledScenarioSessionResponse result = action.apply(session);
				session.lastAccessedAt = clock.instant();
				session.commandResults.put(commandKey, CommandOutcome.success(fingerprint, result));
				return result;
			} catch (ScenarioTransitionConflictException exception) {
				session.lastAccessedAt = clock.instant();
				session.commandResults.put(commandKey,
						CommandOutcome.conflict(fingerprint, exception.getCode(), exception.getMessage()));
				throw exception;
			}
		}
	}

	private void assertCurrent(Session session, ControlledScenarioCommandRequest request) {
		if (!session.epochId.equals(request.expectedEpochId())) {
			throw conflict("STALE_EPOCH", "The session has been reset. Reload its current state before retrying.");
		}
		if (session.revision != request.expectedRevision()) {
			throw conflict("STALE_REVISION", "The session changed. Reload its current state before retrying.");
		}
	}

	private Session ownedSession(AuthenticatedUser principal, UUID sessionId) {
		Owner owner = owner(principal);
		Session session = sessions.get(sessionId);
		if (session == null) throw new ScenarioSessionNotFoundException();
		synchronized (session) {
			if (expired(session, clock.instant())) {
				sessions.remove(sessionId, session);
				throw new ScenarioSessionNotFoundException();
			}
			assertOwner(session, owner);
			authorizedBindings(principal, owner);
			session.lastAccessedAt = clock.instant();
			return session;
		}
	}

	private Owner owner(AuthenticatedUser principal) {
		requireEnabledAndConfigured();
		if (principal == null || principal.id() == null) throw new SessionEndedException();
		UserAccount account = users.findForPermissionCheck(principal.id()).orElseThrow(SessionEndedException::new);
		if (account.getStatus() != AccountStatus.ACTIVE) throw new SessionEndedException();
		Company company = account.getCompany();
		if (company == null || !company.isActive()) throw new ControlledScenarioAccessDeniedException();
		if (!company.getId().equals(properties.getDemoCompanyId())
				|| !properties.getAllowedUserIds().contains(account.getId())) {
			throw new ControlledScenarioAccessDeniedException();
		}
		return new Owner(account.getId(), company.getId());
	}

	private List<ShipmentBinding> authorizedBindings(AuthenticatedUser principal, Owner owner) {
		List<ShipmentBinding> bindings = new ArrayList<>(2);
		bindings.add(binding(principal, owner, "A", properties.getShipmentAId(), SHIPMENT_A_INDEX));
		bindings.add(binding(principal, owner, "B", properties.getShipmentBId(), SHIPMENT_B_INDEX));
		return List.copyOf(bindings);
	}

	private ShipmentBinding binding(AuthenticatedUser principal, Owner owner, String key, Long shipmentId, int index) {
		try {
			shipmentService.authorizeView(principal, shipmentId);
		} catch (ShipmentDetailForbiddenException | ShipmentNotFoundException exception) {
			throw new ScenarioSessionNotFoundException();
		}
		Shipment shipment = shipmentRepository.findByIdAndCompanyId(shipmentId, owner.companyId())
				.orElseThrow(ScenarioSessionNotFoundException::new);
		if (shipment.getStatus() != ShipmentStatus.ACTIVE) throw new ScenarioSessionNotFoundException();
		String scenarioReference = scenario.shipments().get(index).shipment().shipmentReference();
		return new ShipmentBinding(key, shipmentId, shipment.getShipmentReference(), scenarioReference);
	}

	private void requireEnabledAndConfigured() {
		if (!properties.isEnabled()) {
			throw unavailable("SCENARIO_DISABLED", HttpStatus.NOT_FOUND.value(), "Controlled scenarios are not available.");
		}
		if (!properties.isConfigured()) {
			throw unavailable("SCENARIO_NOT_CONFIGURED", HttpStatus.SERVICE_UNAVAILABLE.value(),
					"Controlled scenario access is not configured for this environment.");
		}
	}

	private static void validateCommandKey(String commandKey) {
		if (commandKey == null || commandKey.isBlank() || commandKey.length() > 128) {
			throw new ControlledScenarioInvalidRequestException(
					"Idempotency-Key must contain 1 to 128 non-whitespace characters.");
		}
	}

	private void purgeExpired(Instant now) {
		sessions.forEach((id, session) -> {
			synchronized (session) {
				if (expired(session, now)) sessions.remove(id, session);
			}
		});
	}

	private boolean expired(Session session, Instant now) {
		return !now.isBefore(session.lastAccessedAt.plus(properties.getSessionTtl()));
	}

	private static void assertOwner(Session session, Owner owner) {
		if (!session.ownerUserId.equals(owner.userId()) || !session.ownerCompanyId.equals(owner.companyId())) {
			throw new ScenarioSessionNotFoundException();
		}
	}

	private ControlledScenarioSessionResponse response(UUID sessionId, Session session) {
		boolean canStart = session.state == ControlledScenarioSessionState.UNSTARTED;
		boolean canAdvance = session.state == ControlledScenarioSessionState.ACTIVE
				&& session.stageOrder < scenario.stages().size() - 1;
		boolean canReset = session.state != ControlledScenarioSessionState.UNSTARTED;
		return new ControlledScenarioSessionResponse(API_VERSION, sessionId, scenario.scenarioId(),
				scenario.contractVersion(), session.state, session.epochId, session.revision,
				new ControlledScenarioCapabilities(true, canStart, canAdvance, canReset, false),
			session.shipments, session.snapshot, session.emailSubmissions.containsKey(session.epochId)
				? session.emailSubmissions.get(session.epochId).status() : ControlledScenarioEmailStatus.NOT_TRIGGERED);
	}

	private ControlledScenarioSnapshot createSnapshot(Session session, int stageOrder, long revision) {
		Stage stage = scenario.stages().get(stageOrder);
		Clock simulationClock = Clock.fixed(stage.cutoff(), ZoneOffset.UTC);
		List<ControlledScenarioSnapshot.ShipmentRead> shipmentReads = new ArrayList<>(2);
		for (int index = 0; index < scenario.shipments().size(); index++) {
			shipmentReads.add(shipmentRead(session.shipments.get(index), scenario.shipments().get(index), stage,
					simulationClock));
		}
		MaritimeContextResponse context = maritimeContext.forSyntheticScenario(scenario,
				scenario.shipments().getFirst(), stage.cutoff());
		return new ControlledScenarioSnapshot(UUID.randomUUID(), session.epochId, revision,
				ControlledScenarioStage.fromOrder(stageOrder), stageOrder, scenario.stages().size(), stage.id(),
				stage.cutoff(), stage.cutoff(), shipmentReads,
				List.of(observationRead(stage.mother()), observationRead(stage.feeder())), context);
	}

	private ControlledScenarioSnapshot.ShipmentRead shipmentRead(ShipmentBinding binding, ShipmentInput input,
			Stage stage, Clock simulationClock) {
		var shipment = input.shipment();
		var calculation = connectionRisk.assessControlledSnapshot(shipment.plannedMotherArrivalAt(),
				shipment.transshipmentPort(), shipment.plannedFeederDepartureAt(), stage.mother().position(),
				stage.feeder().position(), SyntheticDisruptionScenarioFixture.PORT_COORDINATES, simulationClock);
		TransshipmentArrivalDeadlineResponse deadline = new TransshipmentArrivalDeadlineResponse(
				input.deadline(), input.expectedTransshipmentPort(),
				shipment.transshipmentPort().strip().equals(input.expectedTransshipmentPort().strip()));
		DelayComparison impact = DelayComparison.of(calculation.arrivalComparison(), deadline);
		return new ControlledScenarioSnapshot.ShipmentRead(binding.scenarioShipmentKey(), binding.shipmentId(),
				input.shipment().shipmentReference(), shipment.transshipmentPort(), shipment.plannedMotherArrivalAt(),
				shipment.plannedFeederDepartureAt(), calculation.planned(), calculation.remaining(),
				calculation.arrivalComparison(), calculation.risk(), impact);
	}

	private static ControlledScenarioSnapshot.AisObservationRead observationRead(AisObservation observation) {
		AisPosition position = observation.position();
		return new ControlledScenarioSnapshot.AisObservationRead(observation.vesselRole(), observation.observedAt(),
				observation.receivedAt(), observation.sourceMode(), position);
	}

	private static ScenarioTransitionConflictException conflict(String code, String message) {
		return new ScenarioTransitionConflictException(code, message);
	}

	private static ControlledScenarioUnavailableException unavailable(String code, int status, String message) {
		return new ControlledScenarioUnavailableException(code, status, message);
	}

	private record Owner(Long userId, Long companyId) { }

	private static final class Session {
		private final Long ownerUserId;
		private final Long ownerCompanyId;
		private final Long anchorShipmentId;
		private final List<ShipmentBinding> shipments;
		private final Map<String, CommandOutcome> commandResults = new java.util.LinkedHashMap<>();
		private final Map<UUID, EmailSubmission> emailSubmissions = new LinkedHashMap<>();
		private UUID epochId = UUID.randomUUID();
		private long revision;
		private int stageOrder = -1;
		private ControlledScenarioSessionState state = ControlledScenarioSessionState.UNSTARTED;
		private ControlledScenarioSnapshot snapshot;
		private Instant lastAccessedAt;

		private Session(Long ownerUserId, Long ownerCompanyId, Long anchorShipmentId,
				List<ShipmentBinding> shipments, Instant createdAt) {
			this.ownerUserId = ownerUserId;
			this.ownerCompanyId = ownerCompanyId;
			this.anchorShipmentId = anchorShipmentId;
			this.shipments = List.copyOf(shipments);
			this.lastAccessedAt = createdAt;
		}
	}

	private record EmailSubmission(long revision, ControlledScenarioEmailStatus status) { }

	private record CommandOutcome(String fingerprint, ControlledScenarioSessionResponse response,
			String conflictCode, String conflictMessage) {
		private static CommandOutcome success(String fingerprint, ControlledScenarioSessionResponse response) {
			return new CommandOutcome(fingerprint, response, null, null);
		}
		private static CommandOutcome conflict(String fingerprint, String code, String message) {
			return new CommandOutcome(fingerprint, null, code, message);
		}
	}
}
