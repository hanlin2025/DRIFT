package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.drift.backend.account.AccountRole;
import com.drift.backend.account.Role;
import com.drift.backend.account.UserAccount;

class ShipmentRiskPersistenceModelTest {

	private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

	@Test
	void nullRiskPreservesLastValidSnapshotAndSequenceStartsAtOne() {
		Shipment shipment = shipment();
		ShipmentRiskState state = new ShipmentRiskState(shipment, ShipmentRiskBaselineMode.SILENT_FIRST_VALID, NOW);

		assertThat(state.recordValidEvaluation(null, NOW.plusSeconds(60))).isFalse();
		assertThat(state.getLastRiskLevel()).isNull();
		assertThat(state.getEvaluatedAt()).isNull();
		assertThat(state.allocateTransitionSequence()).isEqualTo(1);
		assertThat(state.allocateTransitionSequence()).isEqualTo(2);
		assertThat(state.getBaselineMode()).isEqualTo(ShipmentRiskBaselineMode.SILENT_FIRST_VALID);
	}

	@Test
	void transitionRequiresNullPreviousOnlyForInitialAndSnapshotsExplanation() {
		Shipment shipment = shipment();
		ShipmentRisk risk = new ShipmentRisk(ShipmentRisk.Level.HIGH, "stable explanation");
		ShipmentRiskTransition initial = new ShipmentRiskTransition(shipment, 1,
				ShipmentRiskTransitionType.INITIAL, null, risk, NOW);
		ShipmentRiskTransition rise = new ShipmentRiskTransition(shipment, 2,
				ShipmentRiskTransitionType.RISE, ShipmentRisk.Level.LOW, risk, NOW.plusSeconds(1));

		assertThat(initial.getPreviousRiskLevel()).isNull();
		assertThat(initial.getRiskLevel()).isEqualTo(ShipmentRisk.Level.HIGH);
		assertThat(rise.getPreviousRiskLevel()).isEqualTo(ShipmentRisk.Level.LOW);
		assertThat(rise.getExplanation()).isEqualTo("stable explanation");
		assertThatThrownBy(() -> new ShipmentRiskTransition(shipment, 3,
				ShipmentRiskTransitionType.INITIAL, ShipmentRisk.Level.MEDIUM, risk, NOW))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void deliveryLeaseAndRetryLifecycleRejectsStaleCompletion() {
		ShipmentRiskTransition transition = new ShipmentRiskTransition(shipment(), 1,
				ShipmentRiskTransitionType.INITIAL, null,
				new ShipmentRisk(ShipmentRisk.Level.HIGH, "snapshot"), NOW);
		UserAccount user = new UserAccount("Risk User", "risk@example.com", "hash",
				new AccountRole(Role.FREIGHT_FORWARDER), null);
		ShipmentRiskAlertDelivery delivery = new ShipmentRiskAlertDelivery(transition, user, NOW);
		UUID first = UUID.randomUUID();
		UUID reclaimed = UUID.randomUUID();

		assertThat(delivery.claim(first, NOW.plusSeconds(10), NOW)).isTrue();
		assertThat(delivery.claim(UUID.randomUUID(), NOW.plusSeconds(20), NOW.plusSeconds(1))).isFalse();
		assertThat(delivery.claim(reclaimed, NOW.plusSeconds(20), NOW.plusSeconds(11))).isTrue();
		assertThat(delivery.recordAttempt(first, NOW.plusSeconds(12))).isFalse();
		assertThat(delivery.recordAttempt(reclaimed, NOW.plusSeconds(12))).isTrue();
		assertThat(delivery.markFailure(reclaimed, ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE,
				"sanitized", NOW.plusSeconds(60), NOW.plusSeconds(13))).isTrue();
		assertThat(delivery.getAttemptCount()).isEqualTo(1);
		assertThat(delivery.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
		assertThat(delivery.getLastFailure()).isEqualTo("sanitized");
	}

	private static Shipment shipment() {
		return new Shipment(null, null, "RISK-1", "Singapore", "Rotterdam", "Singapore", "Mother",
				OffsetDateTime.parse("2026-10-15T08:00:00Z"), "Feeder",
				OffsetDateTime.parse("2026-10-16T08:00:00Z"));
	}
}
