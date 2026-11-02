package com.drift.backend.shipment;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
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
import com.drift.backend.shipment.exception.ScenarioSessionNotFoundException;
import com.drift.backend.shipment.exception.ScenarioTransitionConflictException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;

/**
 * Manually controlled, process-local scenario sessions. A session contains only its stage index;
 * shipment, AIS, evidence, risk, and impact data are immutable fixture projections.
 */
@Service
@Transactional(readOnly = true)
public class ControlledScenarioSessionService {
	private final Scenario scenario;
	private final UserAccountRepository users;
	private final ShipmentConnectionRisk connectionRisk;
	private final MaritimeContextService maritimeContext;
	private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();

	@Autowired
	public ControlledScenarioSessionService(UserAccountRepository users, ShipmentConnectionRisk connectionRisk,
			MaritimeContextService maritimeContext) {
		this(users, connectionRisk, maritimeContext, SyntheticDisruptionScenarioFixture.create("Api"));
	}

	ControlledScenarioSessionService(UserAccountRepository users, ShipmentConnectionRisk connectionRisk,
			MaritimeContextService maritimeContext, Scenario scenario) {
		if (scenario == null) throw new IllegalStateException("The controlled scenario fixture is unavailable.");
		this.scenario = scenario.validate();
		this.users = users;
		this.connectionRisk = connectionRisk;
		this.maritimeContext = maritimeContext;
	}

	/** Idempotent Start: create the named session at BASELINE, or return its current stage unchanged. */
	public ControlledScenarioSnapshot start(AuthenticatedUser principal, UUID sessionId) {
		Owner owner = owner(principal);
		Session created = new Session(owner.userId(), owner.companyId());
		Session session = sessions.putIfAbsent(sessionId, created);
		if (session == null) session = created;
		assertOwner(session, owner);
		synchronized (session) {
			return snapshot(sessionId, session);
		}
	}

	public ControlledScenarioSnapshot get(AuthenticatedUser principal, UUID sessionId) {
		Session session = ownedSession(principal, sessionId);
		synchronized (session) {
			return snapshot(sessionId, session);
		}
	}

	/** Advances only the snapshot the caller saw; a retry using any earlier ID from this session is a no-op. */
	public ControlledScenarioSnapshot next(AuthenticatedUser principal, UUID sessionId, String expectedSnapshotId) {
		Session session = ownedSession(principal, sessionId);
		synchronized (session) {
			String currentSnapshotId = snapshotId(sessionId, session.stageOrder);
			if (currentSnapshotId.equals(expectedSnapshotId)) {
				if (session.stageOrder == scenario.stages().size() - 1) {
					throw new ScenarioTransitionConflictException("SCENARIO_COMPLETE",
							"The scenario is already at WORSENED.");
				}
				session.stageOrder++;
			} else {
				int requestedOrder = stageOrder(sessionId, expectedSnapshotId);
				if (requestedOrder < 0 || requestedOrder >= session.stageOrder) {
					throw new ScenarioTransitionConflictException("STALE_SNAPSHOT",
							"Next requires the current snapshotId.");
				}
				// A retried action from an earlier snapshot returns the current state without advancing again.
			}
			return snapshot(sessionId, session);
		}
	}

	/** Idempotent Reset: return this session to its original BASELINE snapshot. */
	public ControlledScenarioSnapshot reset(AuthenticatedUser principal, UUID sessionId) {
		Session session = ownedSession(principal, sessionId);
		synchronized (session) {
			session.stageOrder = 0;
			return snapshot(sessionId, session);
		}
	}

	private Session ownedSession(AuthenticatedUser principal, UUID sessionId) {
		Owner owner = owner(principal);
		Session session = sessions.get(sessionId);
		if (session == null) throw new ScenarioSessionNotFoundException();
		assertOwner(session, owner);
		return session;
	}

	private Owner owner(AuthenticatedUser principal) {
		if (principal == null || principal.id() == null) throw new SessionEndedException();
		UserAccount account = users.findForPermissionCheck(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = account.getCompany();
		if (company == null || !company.isActive()) throw new ShipmentAccessForbiddenException();
		return new Owner(account.getId(), company.getId());
	}

	private static void assertOwner(Session session, Owner owner) {
		if (!session.ownerUserId.equals(owner.userId()) || !session.ownerCompanyId.equals(owner.companyId())) {
			// Conceal session existence from other users and tenants.
			throw new ScenarioSessionNotFoundException();
		}
	}

	private ControlledScenarioSnapshot snapshot(UUID sessionId, Session session) {
		Stage stage = scenario.stages().get(session.stageOrder);
		ControlledScenarioStage stageName = ControlledScenarioStage.fromOrder(session.stageOrder);
		Clock simulationClock = Clock.fixed(stage.cutoff(), ZoneOffset.UTC);
		List<ControlledScenarioSnapshot.ShipmentRead> shipmentReads = scenario.shipments().stream()
				.map(input -> shipmentRead(input, stage, simulationClock)).toList();
		MaritimeContextResponse context = maritimeContext.forSyntheticScenario(scenario,
				scenario.shipments().getFirst(), stage.cutoff());
		return new ControlledScenarioSnapshot(sessionId, scenario.scenarioId(), scenario.contractVersion(), stageName,
				session.stageOrder, stage.id(), snapshotId(sessionId, session.stageOrder), stage.cutoff(), stage.cutoff(),
				session.stageOrder < scenario.stages().size() - 1, true, shipmentReads,
				List.of(observationRead(stage.mother()), observationRead(stage.feeder())), context);
	}

	private ControlledScenarioSnapshot.ShipmentRead shipmentRead(ShipmentInput input, Stage stage, Clock simulationClock) {
		var shipment = input.shipment();
		var calculation = connectionRisk.assessControlledSnapshot(shipment.plannedMotherArrivalAt(),
				shipment.transshipmentPort(), shipment.plannedFeederDepartureAt(), stage.mother().position(),
				stage.feeder().position(), SyntheticDisruptionScenarioFixture.PORT_COORDINATES, simulationClock);
		TransshipmentArrivalDeadlineResponse deadline = new TransshipmentArrivalDeadlineResponse(
				input.deadline(), input.expectedTransshipmentPort(),
				shipment.transshipmentPort().strip().equals(input.expectedTransshipmentPort().strip()));
		DelayComparison impact = DelayComparison.of(calculation.arrivalComparison(), deadline);
		return new ControlledScenarioSnapshot.ShipmentRead(shipment.shipmentReference(), shipment.transshipmentPort(),
				shipment.plannedMotherArrivalAt(), shipment.plannedFeederDepartureAt(), calculation.planned(),
				calculation.remaining(), calculation.arrivalComparison(), calculation.risk(), impact);
	}

	private static ControlledScenarioSnapshot.AisObservationRead observationRead(AisObservation observation) {
		AisPosition position = observation.position();
		return new ControlledScenarioSnapshot.AisObservationRead(observation.vesselRole(), observation.observedAt(),
				observation.receivedAt(), observation.sourceMode(), position);
	}

	private static String snapshotId(UUID sessionId, int order) {
		return sessionId + ":" + SyntheticDisruptionScenarioFixture.CONTRACT_VERSION + ":"
				+ ControlledScenarioStage.fromOrder(order).name();
	}

	private static int stageOrder(UUID sessionId, String snapshotId) {
		if (snapshotId == null) return -1;
		String prefix = sessionId + ":" + SyntheticDisruptionScenarioFixture.CONTRACT_VERSION + ":";
		if (!snapshotId.startsWith(prefix)) return -1;
		try {
			return switch (ControlledScenarioStage.valueOf(snapshotId.substring(prefix.length()))) {
				case BASELINE -> 0;
				case CHANGE -> 1;
				case WORSENED -> 2;
			};
		} catch (IllegalArgumentException exception) {
			return -1;
		}
	}

	private record Owner(Long userId, Long companyId) { }

	private static final class Session {
		private final Long ownerUserId;
		private final Long ownerCompanyId;
		private int stageOrder;

		private Session(Long ownerUserId, Long ownerCompanyId) {
			this.ownerUserId = ownerUserId;
			this.ownerCompanyId = ownerCompanyId;
		}
	}
}
