package com.drift.backend.shipment;

import java.time.Duration;

import org.springframework.stereotype.Component;

/** Fixed, bounded retry policy for completed SMTP attempts. */
@Component
public class ShipmentRiskAlertRetryPolicy {

	public RetryDecision afterFailure(int completedAttemptCount) {
		return switch (completedAttemptCount) {
			case 1 -> RetryDecision.retryAfter(Duration.ofMinutes(1));
			case 2 -> RetryDecision.retryAfter(Duration.ofMinutes(5));
			case 3 -> RetryDecision.retryAfter(Duration.ofMinutes(15));
			default -> RetryDecision.exhausted();
		};
	}

	public record RetryDecision(ShipmentRiskAlertDeliveryStatus status, Duration delay) {
		static RetryDecision retryAfter(Duration delay) {
			return new RetryDecision(ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE, delay);
		}

		static RetryDecision exhausted() {
			return new RetryDecision(ShipmentRiskAlertDeliveryStatus.EXHAUSTED, null);
		}
	}
}
