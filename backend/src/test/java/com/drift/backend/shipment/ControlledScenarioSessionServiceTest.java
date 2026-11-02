package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.Role;
import com.drift.backend.account.AccountStatus;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.company.Company;
import com.drift.backend.maritimecontext.MaritimeContextService;
import com.drift.backend.shipment.ControlledScenarioSessionResponse.ShipmentBinding;
import com.drift.backend.shipment.exception.ControlledScenarioUnavailableException;
import com.drift.backend.shipment.exception.ControlledScenarioAccessDeniedException;
import com.drift.backend.shipment.exception.ScenarioSessionNotFoundException;
import com.drift.backend.shipment.exception.ScenarioTransitionConflictException;

class ControlledScenarioSessionServiceTest {
	private UserAccountRepository users;
	private LatestAisPositions livePositions;
	private ShipmentService shipmentService;
	private ShipmentRepository shipmentRepository;
	private ShipmentConnectionRisk connectionRisk;
	private MaritimeContextService maritimeContext;
	private ControlledScenarioProperties properties;
	private ControlledScenarioSessionService sessions;
	private AuthenticatedUser owner;
	private AuthenticatedUser otherTenant;

	@BeforeEach
	void setUp() {
		users = mock(UserAccountRepository.class);
		livePositions = mock(LatestAisPositions.class);
		shipmentService = mock(ShipmentService.class);
		shipmentRepository = mock(ShipmentRepository.class);
		Company ownerCompany = company(10L);
		Company otherCompany = company(20L);
		UserAccount ownerAccount = account(1L, ownerCompany);
		UserAccount otherAccount = account(2L, otherCompany);
		when(users.findForPermissionCheck(1L)).thenReturn(Optional.of(ownerAccount));
		when(users.findForPermissionCheck(2L)).thenReturn(Optional.of(otherAccount));
		owner = new AuthenticatedUser(1L, "owner@example.invalid", Role.LOGISTICS_MANAGER, Instant.MAX);
		otherTenant = new AuthenticatedUser(2L, "other@example.invalid", Role.LOGISTICS_MANAGER, Instant.MAX);
		properties = configuredProperties();
		bindShipment(100L, 10L, "AUTHORIZED-SHIPMENT-A");
		bindShipment(200L, 10L, "AUTHORIZED-SHIPMENT-B");
		var coordinates = PortCatalog.of(Map.of());
		connectionRisk = new ShipmentConnectionRisk(new ConnectionWindowService(), livePositions,
				new ArrivalEstimateCalculator(coordinates), Clock.systemUTC());
		maritimeContext = new MaritimeContextService(false, List.of());
		sessions = new ControlledScenarioSessionService(users, shipmentService, shipmentRepository, connectionRisk,
				maritimeContext, properties, Clock.fixed(Instant.parse("2026-11-02T00:00:00Z"), ZoneOffset.UTC),
				com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.create("Api"));
	}

	@Test
	void refusesToInitializeWhenTheRequiredScenarioFixtureIsMissing() {
		assertThatThrownBy(() -> new ControlledScenarioSessionService(users, shipmentService, shipmentRepository,
				connectionRisk, maritimeContext, properties, Clock.systemUTC(), null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("fixture is unavailable");
	}

	@Test
	void defaultDisabledAndMissingConfigurationFailClosed() {
		properties.setEnabled(false);
		assertThatThrownBy(() -> sessions.create(owner, UUID.randomUUID(), 100L))
				.isInstanceOf(ControlledScenarioUnavailableException.class).extracting("code").isEqualTo("SCENARIO_DISABLED");
		properties.setEnabled(true);
		properties.setAllowedUserIds(Set.of());
		assertThatThrownBy(() -> sessions.create(owner, UUID.randomUUID(), 100L))
				.isInstanceOf(ControlledScenarioUnavailableException.class).extracting("code").isEqualTo("SCENARIO_NOT_CONFIGURED");
		verifyNoInteractions(livePositions);
	}

	@Test
	void createsUnstartedThenCalculatesAllThreeStagesAndResetsIntoANewEpoch() {
		UUID sessionId = UUID.randomUUID();
		ControlledScenarioSessionResponse created = create(sessionId);
		assertThat(created.apiVersion()).isEqualTo("1");
		assertThat(created.state()).isEqualTo(ControlledScenarioSessionState.UNSTARTED);
		assertThat(created.revision()).isZero();
		assertThat(created.snapshot()).isNull();
		assertThat(created.capabilities().canStart()).isTrue();
		assertThat(created.shipments()).extracting(ShipmentBinding::scenarioShipmentKey).containsExactly("A", "B");
		assertThat(created.shipments()).extracting(ShipmentBinding::shipmentId).containsExactly(100L, 200L);
		assertThat(created.shipments()).extracting(ShipmentBinding::shipmentReference)
				.containsExactly("AUTHORIZED-SHIPMENT-A", "AUTHORIZED-SHIPMENT-B");

		ControlledScenarioSessionResponse baseline = sessions.start(owner, sessionId, command(created, "start-1"), "start-1");
		assertThat(baseline.state()).isEqualTo(ControlledScenarioSessionState.ACTIVE);
		assertThat(baseline.snapshot().stage()).isEqualTo(ControlledScenarioStage.BASELINE);
		assertThat(baseline.snapshot().revision()).isEqualTo(1);
		assertThat(baseline.snapshot().simulationCutoffUtc()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
		assertThat(baseline.snapshot().mapPlaybackCutoffUtc()).isEqualTo(baseline.snapshot().simulationCutoffUtc());
		assertThat(baseline.snapshot().snapshotId()).isNotNull();
		assertThat(baseline.snapshot().shipments()).extracting(ControlledScenarioSnapshot.ShipmentRead::risk)
				.extracting(risk -> risk.level().name()).containsExactly("LOW", "LOW");
		assertThat(baseline.snapshot().shipments()).extracting(ControlledScenarioSnapshot.ShipmentRead::businessImpact)
				.extracting(impact -> impact.outcome().name()).containsExactly("NO_PREDICTED_IMPACT", "NO_PREDICTED_IMPACT");
		assertThat(baseline.snapshot().shipments()).extracting(ControlledScenarioSnapshot.ShipmentRead::sourceShipmentId)
				.containsExactly(100L, 200L);
		assertThat(baseline.snapshot().aisObservations()).allSatisfy(row -> {
			assertThat(row.sourceMode()).isEqualTo("SYNTHETIC_DEMONSTRATION");
			assertThat(row.receivedAtUtc()).isEqualTo(row.position().ingestedAt());
		});

		ControlledScenarioSessionResponse repeatedStart = sessions.start(owner, sessionId, command(created, "start-1"), "start-1");
		assertThat(repeatedStart).isEqualTo(baseline);
		assertThat(sessions.start(owner, sessionId, command(baseline, "start-2"), "start-2")).isEqualTo(baseline);

		ControlledScenarioSessionResponse change = sessions.next(owner, sessionId, command(baseline, "next-1"), "next-1");
		assertThat(change.snapshot().stage()).isEqualTo(ControlledScenarioStage.CHANGE);
		assertThat(change.snapshot().revision()).isEqualTo(2);
		assertThat(change.snapshot().simulationCutoffUtc()).isEqualTo(Instant.parse("2026-10-01T04:00:00Z"));
		assertThat(change.snapshot().shipments()).extracting(ControlledScenarioSnapshot.ShipmentRead::risk)
				.extracting(risk -> risk.level().name()).containsExactly("MEDIUM", "MEDIUM");
		assertThat(change.snapshot().shipments()).extracting(ControlledScenarioSnapshot.ShipmentRead::arrivalEstimate)
				.extracting(estimate -> estimate.estimatedArrivalAt().toInstant())
				.containsExactly(Instant.parse("2026-10-01T16:00:00Z"), Instant.parse("2026-10-01T16:00:00Z"));
		assertThat(change.snapshot().shipments()).extracting(ControlledScenarioSnapshot.ShipmentRead::businessImpact)
				.extracting(impact -> impact.outcome().name()).containsExactly("EXCEEDS_DEADLINE", "NO_PREDICTED_IMPACT");

		ControlledScenarioSessionResponse worsened = sessions.next(owner, sessionId, command(change, "next-2"), "next-2");
		assertThat(worsened.state()).isEqualTo(ControlledScenarioSessionState.COMPLETE);
		assertThat(worsened.capabilities().canAdvance()).isFalse();
		assertThat(worsened.snapshot().stage()).isEqualTo(ControlledScenarioStage.WORSENED);
		assertThat(worsened.snapshot().revision()).isEqualTo(3);
		assertThat(worsened.snapshot().simulationCutoffUtc()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
		assertThat(worsened.snapshot().shipments()).extracting(ControlledScenarioSnapshot.ShipmentRead::risk)
				.extracting(risk -> risk.level().name()).containsExactly("HIGH", "HIGH");
		assertThat(worsened.snapshot().shipments()).extracting(ControlledScenarioSnapshot.ShipmentRead::businessImpact)
				.extracting(impact -> impact.outcome().name()).containsExactly("EXCEEDS_DEADLINE", "NO_PREDICTED_IMPACT");
		assertThatThrownBy(() -> sessions.next(owner, sessionId, command(worsened, "next-final"), "next-final"))
				.isInstanceOf(ScenarioTransitionConflictException.class).extracting("code").isEqualTo("SCENARIO_COMPLETE");

		ControlledScenarioSessionResponse reset = sessions.reset(owner, sessionId, command(worsened, "reset-1"), "reset-1");
		assertThat(reset.state()).isEqualTo(ControlledScenarioSessionState.UNSTARTED);
		assertThat(reset.snapshot()).isNull();
		assertThat(reset.epochId()).isNotEqualTo(created.epochId());
		assertThat(reset.revision()).isZero();
		assertThat(sessions.reset(owner, sessionId, command(reset, "reset-2"), "reset-2")).isEqualTo(reset);
		verifyNoInteractions(livePositions);
	}

	@Test
	void resetFencesOldCommandsAndSnapshotsAndDuplicateKeysReplayTheOriginalOutcome() {
		UUID sessionId = UUID.randomUUID();
		ControlledScenarioSessionResponse created = create(sessionId);
		ControlledScenarioSessionResponse baseline = sessions.start(owner, sessionId, command(created, "start"), "start");
		ControlledScenarioCommandRequest oldBaselineCommand = command(baseline, "old-next");
		ControlledScenarioSessionResponse change = sessions.next(owner, sessionId, oldBaselineCommand, "old-next");
		ControlledScenarioSessionResponse reset = sessions.reset(owner, sessionId, command(change, "reset"), "reset");
		assertThat(reset.epochId()).isNotEqualTo(baseline.epochId());

		// The retry returns its original response but cannot mutate or impersonate the new epoch.
		assertThat(sessions.next(owner, sessionId, oldBaselineCommand, "old-next")).isEqualTo(change);
		assertThat(sessions.get(owner, sessionId)).isEqualTo(reset);
		assertThatThrownBy(() -> sessions.next(owner, sessionId, oldBaselineCommand, "old-next-alias"))
				.isInstanceOf(ScenarioTransitionConflictException.class).extracting("code").isEqualTo("STALE_EPOCH");
		assertThatThrownBy(() -> sessions.next(owner, sessionId, command(reset, "not-started-next"), "not-started-next"))
				.isInstanceOf(ScenarioTransitionConflictException.class).extracting("code").isEqualTo("SCENARIO_NOT_STARTED");

		ControlledScenarioSessionResponse nextBaseline = sessions.start(owner, sessionId, command(reset, "start-new"), "start-new");
		assertThat(nextBaseline.snapshot().snapshotId()).isNotEqualTo(baseline.snapshot().snapshotId());
		assertThat(nextBaseline.snapshot().epochId()).isEqualTo(reset.epochId());
		assertThatThrownBy(() -> sessions.start(owner, sessionId, command(nextBaseline, "old-next"), "old-next"))
				.isInstanceOf(ScenarioTransitionConflictException.class).extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
	}

	@Test
	void concurrentCommandsCommitAtMostOneTransitionAndSessionsRemainIsolated() throws Exception {
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		ControlledScenarioSessionResponse firstCreated = create(firstId);
		ControlledScenarioSessionResponse secondCreated = create(secondId);
		ControlledScenarioSessionResponse firstBaseline = sessions.start(owner, firstId,
				command(firstCreated, "start-a"), "start-a");
		ControlledScenarioSessionResponse secondBaseline = sessions.start(owner, secondId,
				command(secondCreated, "start-b"), "start-b");

		CountDownLatch startTogether = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var firstNext = executor.submit(() -> {
				startTogether.await();
				return sessions.next(owner, firstId, command(firstBaseline, "same-key"), "same-key");
			});
			var repeatedNext = executor.submit(() -> {
				startTogether.await();
				return sessions.next(owner, firstId, command(firstBaseline, "same-key"), "same-key");
			});
			startTogether.countDown();
			assertThat(firstNext.get()).isEqualTo(repeatedNext.get());
			assertThat(firstNext.get().snapshot().stage()).isEqualTo(ControlledScenarioStage.CHANGE);
		}
		assertThat(sessions.get(owner, secondId)).isEqualTo(secondBaseline);

		ControlledScenarioCommandRequest sharedRevision = command(secondBaseline, "different-key-a");
		CountDownLatch distinctTogether = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var one = executor.submit(() -> {
				distinctTogether.await();
				try {
					return new CommandAttempt(sessions.next(owner, secondId, sharedRevision, "different-key-a"), null);
				} catch (ScenarioTransitionConflictException exception) {
					return new CommandAttempt(null, exception.getCode());
				}
			});
			var two = executor.submit(() -> {
				distinctTogether.await();
				try {
					return new CommandAttempt(sessions.next(owner, secondId, sharedRevision, "different-key-b"), null);
				} catch (ScenarioTransitionConflictException exception) {
					return new CommandAttempt(null, exception.getCode());
				}
			});
			distinctTogether.countDown();
			List<CommandAttempt> attempts = List.of(one.get(), two.get());
			assertThat(attempts).filteredOn(attempt -> attempt.response() != null).hasSize(1);
			assertThat(attempts).extracting(CommandAttempt::conflictCode).contains("STALE_REVISION");
		}
		assertThat(sessions.get(owner, secondId).snapshot().stage()).isEqualTo(ControlledScenarioStage.CHANGE);
		assertThatThrownBy(() -> sessions.get(otherTenant, firstId))
				.isInstanceOf(ControlledScenarioAccessDeniedException.class);
		verifyNoInteractions(livePositions);
	}

	@Test
	void idempotencyKeyReuseAndInvalidShipmentAnchorsAreRejected() {
		UUID sessionId = UUID.randomUUID();
		ControlledScenarioSessionResponse created = create(sessionId);
		ControlledScenarioSessionResponse baseline = sessions.start(owner, sessionId, command(created, "start"), "start");
		assertThatThrownBy(() -> sessions.next(owner, sessionId, command(baseline, "start"), "start"))
				.isInstanceOf(ScenarioTransitionConflictException.class).extracting("code").isEqualTo("IDEMPOTENCY_KEY_REUSED");
		assertThatThrownBy(() -> sessions.create(owner, UUID.randomUUID(), 999L))
				.isInstanceOf(ScenarioSessionNotFoundException.class);
		assertThatThrownBy(() -> sessions.create(owner, sessionId, 200L))
				.isInstanceOf(ScenarioTransitionConflictException.class).extracting("code").isEqualTo("SESSION_ID_REUSED");
		verifyNoInteractions(livePositions);
	}

	@Test
	void sessionsExpireAndCapacityRemainsBounded() {
		MutableClock clock = new MutableClock(Instant.parse("2026-11-02T00:00:00Z"));
		properties.setSessionTtl(Duration.ofSeconds(10));
		properties.setMaxSessions(1);
		sessions = new ControlledScenarioSessionService(users, shipmentService, shipmentRepository, connectionRisk,
				maritimeContext, properties, clock, com.drift.backend.maritimecontext.SyntheticDisruptionScenarioFixture.create("Api"));
		UUID first = UUID.randomUUID();
		ControlledScenarioSessionResponse created = create(first);
		assertThatThrownBy(() -> create(UUID.randomUUID()))
				.isInstanceOf(ControlledScenarioUnavailableException.class).extracting("code").isEqualTo("SESSION_CAPACITY");
		clock.advance(Duration.ofSeconds(11));
		assertThatThrownBy(() -> sessions.get(owner, first)).isInstanceOf(ScenarioSessionNotFoundException.class);
		assertThat(create(UUID.randomUUID()).state()).isEqualTo(ControlledScenarioSessionState.UNSTARTED);
	}

	@Test
	void evidenceEligibilityUsesTheSameStageCutoffAndExcludesFutureRows() {
		UUID sessionId = UUID.randomUUID();
		ControlledScenarioSessionResponse created = create(sessionId);
		ControlledScenarioSessionResponse baseline = sessions.start(owner, sessionId, command(created, "start"), "start");
		assertThat(baseline.snapshot().maritimeContext().evidence()).isEmpty();

		ControlledScenarioSessionResponse change = sessions.next(owner, sessionId, command(baseline, "next-1"), "next-1");
		assertThat(change.snapshot().maritimeContext().evidence()).extracting(row -> row.evidenceId())
				.containsExactly("cdg279-v1-revision-1");
		assertThat(change.snapshot().maritimeContext().assessmentCutoffUtc())
				.isEqualTo(change.snapshot().simulationCutoffUtc().toString());
		assertThat(change.snapshot().maritimeContext().evidence()).allSatisfy(row -> {
			assertThat(Instant.parse(row.retrievedAtUtc())).isBeforeOrEqualTo(change.snapshot().simulationCutoffUtc());
			if (row.observedAt() != null) {
				assertThat(Instant.parse(row.observedAt())).isBeforeOrEqualTo(change.snapshot().simulationCutoffUtc());
			}
		});

		ControlledScenarioSessionResponse worsened = sessions.next(owner, sessionId, command(change, "next-2"), "next-2");
		assertThat(worsened.snapshot().maritimeContext().evidence()).extracting(row -> row.evidenceId())
				.containsExactly("cdg279-v1-revision-1", "cdg279-v1-notice-2", "cdg279-v1-weather-2",
						"cdg279-v1-notice-other-area-2", "cdg279-v1-stale-negative-2", "cdg279-v1-unknown-negative-2");
		assertThat(worsened.snapshot().maritimeContext().assessmentCutoffUtc())
				.isEqualTo(worsened.snapshot().simulationCutoffUtc().toString());
	}

	private ControlledScenarioSessionResponse create(UUID sessionId) {
		return sessions.create(owner, sessionId, 100L);
	}

	private static ControlledScenarioCommandRequest command(ControlledScenarioSessionResponse response, String ignoredKey) {
		return new ControlledScenarioCommandRequest(response.epochId(), response.revision());
	}

	private void bindShipment(long shipmentId, long companyId, String reference) {
		Shipment shipment = mock(Shipment.class);
		when(shipment.getShipmentReference()).thenReturn(reference);
		when(shipment.getStatus()).thenReturn(ShipmentStatus.ACTIVE);
		when(shipmentRepository.findByIdAndCompanyId(shipmentId, companyId)).thenReturn(Optional.of(shipment));
	}

	private static ControlledScenarioProperties configuredProperties() {
		ControlledScenarioProperties properties = new ControlledScenarioProperties();
		properties.setEnabled(true);
		properties.setDemoCompanyId(10L);
		properties.setAllowedUserIds(Set.of(1L));
		properties.setShipmentAId(100L);
		properties.setShipmentBId(200L);
		return properties;
	}

	private static UserAccount account(long id, Company company) {
		UserAccount account = mock(UserAccount.class);
		when(account.getId()).thenReturn(id);
		when(account.getCompany()).thenReturn(company);
		when(account.getStatus()).thenReturn(AccountStatus.ACTIVE);
		return account;
	}

	private record CommandAttempt(ControlledScenarioSessionResponse response, String conflictCode) { }

	private static Company company(long id) {
		Company company = mock(Company.class);
		when(company.getId()).thenReturn(id);
		when(company.isActive()).thenReturn(true);
		return company;
	}

	private static final class MutableClock extends Clock {
		private final AtomicReference<Instant> now;
		private MutableClock(Instant instant) { now = new AtomicReference<>(instant); }
		void advance(Duration duration) { now.updateAndGet(instant -> instant.plus(duration)); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) {
			if (!ZoneOffset.UTC.equals(zone)) throw new UnsupportedOperationException();
			return this;
		}
		@Override public Instant instant() { return now.get(); }
	}
}
