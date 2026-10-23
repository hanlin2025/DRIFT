package com.drift.backend.shipment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ShipmentRiskAlertRetryPolicyTest {

	private final ShipmentRiskAlertRetryPolicy policy = new ShipmentRiskAlertRetryPolicy();

	@Test
	void schedulesTheThreeApprovedRetryDelaysThenExhausts() {
		assertThat(policy.afterFailure(1)).isEqualTo(new ShipmentRiskAlertRetryPolicy.RetryDecision(
				ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE, Duration.ofMinutes(1)));
		assertThat(policy.afterFailure(2)).isEqualTo(new ShipmentRiskAlertRetryPolicy.RetryDecision(
				ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE, Duration.ofMinutes(5)));
		assertThat(policy.afterFailure(3)).isEqualTo(new ShipmentRiskAlertRetryPolicy.RetryDecision(
				ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE, Duration.ofMinutes(15)));
		assertThat(policy.afterFailure(4)).isEqualTo(new ShipmentRiskAlertRetryPolicy.RetryDecision(
				ShipmentRiskAlertDeliveryStatus.EXHAUSTED, null));
		assertThat(policy.afterFailure(5).status()).isEqualTo(ShipmentRiskAlertDeliveryStatus.EXHAUSTED);
	}
}
