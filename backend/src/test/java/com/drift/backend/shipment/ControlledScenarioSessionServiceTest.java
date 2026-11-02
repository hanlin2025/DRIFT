package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.Role;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.ais.position.LatestAisPositions;
import com.drift.backend.company.Company;
import com.drift.backend.maritimecontext.MaritimeContextService;
import com.drift.backend.shipment.exception.ScenarioSessionNotFoundException;
import com.drift.backend.shipment.exception.ScenarioTransitionConflictException;

class ControlledScenarioSessionServiceTest {
	private UserAccountRepository users;
	private LatestAisPositions livePositions;
	private ShipmentConnectionRisk connectionRisk;
	private MaritimeContextService maritimeContext;
	private ControlledScenarioSessionService sessions;
	private AuthenticatedUser owner;
	private AuthenticatedUser otherTenant;

	@BeforeEach
	void setUp() {
		users = mock(UserAccountRepository.class);
		livePositions = mock(LatestAisPositions.class);
		Company ownerCompany = company(10L);
		Company otherCompany = company(20L);
		UserAccount ownerAccount = account(1L, ownerCompany);
		UserAccount otherAccount = account(2L, otherCompany);
		when(users.findForPermissionCheck(1L)).thenReturn(Optional.of(ownerAccount));
		when(users.findForPermissionCheck(2L)).thenReturn(Optional.of(otherAccount));
		owner = new AuthenticatedUser(1L, "owner@example.invalid", Role.LOGISTICS_MANAGER, Instant.MAX);
		otherTenant = new AuthenticatedUser(2L, "other@example.invalid", Role.LOGISTICS_MANAGER, Instant.MAX);
		var coordinates = PortCatalog.of(Map.of());
		connectionRisk = new ShipmentConnectionRisk(new ConnectionWindowService(), livePositions,
				new ArrivalEstimateCalculator(coordinates), Clock.systemUTC());
		maritimeContext = new MaritimeContextService(false, List.of());
		sessions = new ControlledScenarioSessionService(users, connectionRisk, maritimeContext);
	}

	@Test
	void refusesToInitializeWhenTheRequiredScenarioFixtureIsMissing() {
		assertThatThrownBy(() -> new ControlledScenarioSessionService(users, connectionRisk, maritimeContext, null))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("fixture is unavailable");
	}

	@Test
	void progressesThroughCalculatedStagesAndResetsIdempotently() {
		UUID sessionId = UUID.randomUUID();
		ControlledScenarioSnapshot baseline = sessions.start(owner, sessionId);
		assertThat(baseline.stage()).isEqualTo(ControlledScenarioStage.BASELINE);
		assertThat(baseline.stageOrder()).isZero();
		assertThat(baseline.simulationCutoffUtc()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
		assertThat(baseline.mapPlaybackCutoffUtc()).isEqualTo(baseline.simulationCutoffUtc());
		assertThat(baseline.snapshotId()).isEqualTo(sessions.get(owner, sessionId).snapshotId());
		assertThat(baseline.shipments()).extracting(read -> read.risk().level().name()).containsExactly("LOW", "LOW");
		assertThat(baseline.shipments().getFirst().arrivalEstimate().estimatedArrivalAt().toInstant())
				.isEqualTo(Instant.parse("2026-10-01T06:00:00Z"));
		assertThat(baseline.shipments()).extracting(read -> read.businessImpact().outcome().name())
				.containsExactly("NO_PREDICTED_IMPACT", "NO_PREDICTED_IMPACT");
		assertThat(baseline.aisObservations()).allSatisfy(row -> {
			assertThat(row.sourceMode()).isEqualTo("SYNTHETIC_DEMONSTRATION");
			assertThat(row.receivedAtUtc()).isEqualTo(row.position().ingestedAt());
		});

		assertThat(sessions.start(owner, sessionId).snapshotId()).isEqualTo(baseline.snapshotId());
		ControlledScenarioSnapshot change = sessions.next(owner, sessionId, baseline.snapshotId());
		assertThat(change.stage()).isEqualTo(ControlledScenarioStage.CHANGE);
		assertThat(sessions.start(owner, sessionId).snapshotId()).isEqualTo(change.snapshotId());
		assertThat(change.simulationCutoffUtc()).isEqualTo(Instant.parse("2026-10-01T04:00:00Z"));
		assertThat(change.shipments()).extracting(read -> read.risk().level().name()).containsExactly("MEDIUM", "MEDIUM");
		assertThat(change.shipments().getFirst().arrivalEstimate().estimatedArrivalAt().toInstant())
				.isEqualTo(Instant.parse("2026-10-01T16:00:00Z"));
		assertThat(change.shipments()).extracting(read -> read.businessImpact().outcome().name())
				.containsExactly("EXCEEDS_DEADLINE", "NO_PREDICTED_IMPACT");

		// Retrying Next with the baseline identity returns CHANGE and cannot advance twice.
		ControlledScenarioSnapshot retried = sessions.next(owner, sessionId, baseline.snapshotId());
		assertThat(retried.stage()).isEqualTo(ControlledScenarioStage.CHANGE);
		assertThat(retried.snapshotId()).isEqualTo(change.snapshotId());

		ControlledScenarioSnapshot worsened = sessions.next(owner, sessionId, change.snapshotId());
		assertThat(worsened.stage()).isEqualTo(ControlledScenarioStage.WORSENED);
		assertThat(worsened.simulationCutoffUtc()).isEqualTo(Instant.parse("2026-10-01T08:00:00Z"));
		assertThat(worsened.shipments()).extracting(read -> read.risk().level().name()).containsExactly("HIGH", "HIGH");
		assertThat(worsened.shipments().getFirst().arrivalEstimate().estimatedArrivalAt().toInstant())
				.isEqualTo(Instant.parse("2026-10-02T08:00:00Z"));
		assertThat(worsened.shipments()).extracting(read -> read.businessImpact().outcome().name())
				.containsExactly("EXCEEDS_DEADLINE", "NO_PREDICTED_IMPACT");
		assertThatThrownBy(() -> sessions.next(owner, sessionId, worsened.snapshotId()))
				.isInstanceOf(ScenarioTransitionConflictException.class)
				.extracting("code").isEqualTo("SCENARIO_COMPLETE");

		ControlledScenarioSnapshot reset = sessions.reset(owner, sessionId);
		assertThat(reset.stage()).isEqualTo(ControlledScenarioStage.BASELINE);
		assertThat(reset.snapshotId()).isEqualTo(baseline.snapshotId());
		assertThat(sessions.reset(owner, sessionId).snapshotId()).isEqualTo(baseline.snapshotId());
		verifyNoInteractions(livePositions);
	}

	@Test
	void isolatesConcurrentSessionsAndDeniesOtherTenants() throws Exception {
		UUID firstId = UUID.randomUUID();
		UUID secondId = UUID.randomUUID();
		ControlledScenarioSnapshot first = sessions.start(owner, firstId);
		ControlledScenarioSnapshot second = sessions.start(owner, secondId);
		assertThat(first.snapshotId()).isNotEqualTo(second.snapshotId());

		CountDownLatch startTogether = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var firstNext = executor.submit(() -> {
				startTogether.await();
				return sessions.next(owner, firstId, first.snapshotId());
			});
			var retriedNext = executor.submit(() -> {
				startTogether.await();
				return sessions.next(owner, firstId, first.snapshotId());
			});
			startTogether.countDown();
			assertThat(firstNext.get().stage()).isEqualTo(ControlledScenarioStage.CHANGE);
			assertThat(retriedNext.get().stage()).isEqualTo(ControlledScenarioStage.CHANGE);
		}
		assertThat(sessions.get(owner, secondId).stage()).isEqualTo(ControlledScenarioStage.BASELINE);
		assertThatThrownBy(() -> sessions.get(otherTenant, firstId)).isInstanceOf(ScenarioSessionNotFoundException.class);
		assertThatThrownBy(() -> sessions.get(owner, UUID.randomUUID())).isInstanceOf(ScenarioSessionNotFoundException.class);
		assertThatThrownBy(() -> sessions.next(owner, secondId, "not-a-snapshot"))
				.isInstanceOf(ScenarioTransitionConflictException.class).extracting("code").isEqualTo("STALE_SNAPSHOT");
		assertThat(sessions.get(owner, secondId).stage()).isEqualTo(ControlledScenarioStage.BASELINE);
		verifyNoInteractions(livePositions);
	}

	@Test
	void evidenceEligibilityUsesTheSameStageCutoffAndExcludesFutureRows() {
		UUID sessionId = UUID.randomUUID();
		ControlledScenarioSnapshot baseline = sessions.start(owner, sessionId);
		assertThat(baseline.maritimeContext().evidence()).isEmpty();

		ControlledScenarioSnapshot change = sessions.next(owner, sessionId, baseline.snapshotId());
		assertThat(change.maritimeContext().evidence()).extracting(row -> row.evidenceId())
				.containsExactly("cdg279-v1-revision-1");
		assertThat(change.maritimeContext().assessmentCutoffUtc()).isEqualTo(change.simulationCutoffUtc().toString());
		assertThat(change.maritimeContext().evidence()).allSatisfy(row -> {
			assertThat(Instant.parse(row.retrievedAtUtc())).isBeforeOrEqualTo(change.simulationCutoffUtc());
			if (row.observedAt() != null) assertThat(Instant.parse(row.observedAt())).isBeforeOrEqualTo(change.simulationCutoffUtc());
		});

		ControlledScenarioSnapshot worsened = sessions.next(owner, sessionId, change.snapshotId());
		assertThat(worsened.maritimeContext().evidence()).extracting(row -> row.evidenceId())
				.containsExactly("cdg279-v1-revision-1", "cdg279-v1-notice-2", "cdg279-v1-weather-2",
						"cdg279-v1-notice-other-area-2", "cdg279-v1-stale-negative-2", "cdg279-v1-unknown-negative-2");
		assertThat(worsened.maritimeContext().assessmentCutoffUtc()).isEqualTo(worsened.simulationCutoffUtc().toString());
	}

	private static UserAccount account(long id, Company company) {
		UserAccount account = mock(UserAccount.class);
		when(account.getId()).thenReturn(id);
		when(account.getCompany()).thenReturn(company);
		return account;
	}

	private static Company company(long id) {
		Company company = mock(Company.class);
		when(company.getId()).thenReturn(id);
		when(company.isActive()).thenReturn(true);
		return company;
	}
}
