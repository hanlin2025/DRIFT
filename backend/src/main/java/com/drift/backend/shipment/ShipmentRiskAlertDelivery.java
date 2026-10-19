package com.drift.backend.shipment;

import java.time.Instant;
import java.util.UUID;

import com.drift.backend.account.UserAccount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

/** A recipient-specific durable record of attempts and outcome for a risk transition. */
@Entity
@Table(name = "shipment_risk_alert_deliveries", uniqueConstraints = @UniqueConstraint(
		name = "shipment_risk_alert_deliveries_transition_user_key", columnNames = { "transition_id", "user_id" }))
public class ShipmentRiskAlertDelivery {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "transition_id", nullable = false)
	private ShipmentRiskTransition transition;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private UserAccount user;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 24)
	private ShipmentRiskAlertDeliveryStatus status;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "next_attempt_at")
	private Instant nextAttemptAt;

	@Column(name = "sent_at")
	private Instant sentAt;

	@Column(name = "last_failure_at")
	private Instant lastFailureAt;

	/** Caller must persist sanitized failure text, never credentials or full mail payloads. */
	@Column(name = "last_failure")
	private String lastFailure;

	@Column(name = "suppression_reason")
	private String suppressionReason;

	@Column(name = "claim_token")
	private UUID claimToken;

	@Column(name = "claim_until")
	private Instant claimUntil;

	@Version
	@Column(nullable = false)
	private Long version;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected ShipmentRiskAlertDelivery() {
	}

	public ShipmentRiskAlertDelivery(ShipmentRiskTransition transition, UserAccount user, Instant now) {
		if (transition == null || user == null || now == null) {
			throw new IllegalArgumentException("Transition, user, and timestamp are required");
		}
		this.transition = transition;
		this.user = user;
		this.status = ShipmentRiskAlertDeliveryStatus.PENDING;
		this.attemptCount = 0;
		this.nextAttemptAt = now;
		this.createdAt = now;
		this.updatedAt = now;
	}

	/** Acquire or replace an expired lease; workers should claim rows in a transaction. */
	public boolean claim(UUID token, Instant until, Instant now) {
		if (token == null || until == null || now == null || !until.isAfter(now)) {
			throw new IllegalArgumentException("A claim token and future lease expiry are required");
		}
		if (status != ShipmentRiskAlertDeliveryStatus.PENDING
				&& status != ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE) {
			return false;
		}
		if (nextAttemptAt != null && nextAttemptAt.isAfter(now)) {
			return false;
		}
		if (claimUntil != null && claimUntil.isAfter(now)) {
			return false;
		}
		claimToken = token;
		claimUntil = until;
		updatedAt = now;
		return true;
	}

	/** Record an SMTP attempt only while holding the current unexpired claim. */
	public boolean recordAttempt(UUID token, Instant now) {
		if (!ownsLiveClaim(token, now)) {
			return false;
		}
		if (attemptCount >= 4) {
			throw new IllegalStateException("A delivery cannot exceed four attempts");
		}
		attemptCount++;
		updatedAt = now;
		return true;
	}

	public boolean markSent(UUID token, Instant now) {
		if (!ownsLiveClaim(token, now)) {
			return false;
		}
		status = ShipmentRiskAlertDeliveryStatus.SENT;
		sentAt = now;
		nextAttemptAt = null;
		clearClaim();
		updatedAt = now;
		return true;
	}

	public boolean markFailure(UUID token, ShipmentRiskAlertDeliveryStatus failureStatus, String sanitizedFailure,
			Instant nextAttemptAt, Instant now) {
		if (!ownsLiveClaim(token, now)) {
			return false;
		}
		if (failureStatus != ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE
				&& failureStatus != ShipmentRiskAlertDeliveryStatus.EXHAUSTED) {
			throw new IllegalArgumentException("Failure status must be retryable or exhausted");
		}
		if ((failureStatus == ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE) != (nextAttemptAt != null)) {
			throw new IllegalArgumentException("Only retryable failures require a next attempt time");
		}
		status = failureStatus;
		lastFailure = sanitizedFailure;
		lastFailureAt = now;
		this.nextAttemptAt = nextAttemptAt;
		clearClaim();
		updatedAt = now;
		return true;
	}

	public boolean suppress(UUID token, String reason, Instant now) {
		if (!ownsLiveClaim(token, now)) {
			return false;
		}
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("Suppression reason is required");
		}
		status = ShipmentRiskAlertDeliveryStatus.SUPPRESSED;
		suppressionReason = reason;
		nextAttemptAt = null;
		clearClaim();
		updatedAt = now;
		return true;
	}

	private boolean ownsLiveClaim(UUID token, Instant now) {
		return token != null && token.equals(claimToken) && claimUntil != null && claimUntil.isAfter(now);
	}

	private void clearClaim() {
		claimToken = null;
		claimUntil = null;
	}

	public Long getId() { return id; }
	public ShipmentRiskTransition getTransition() { return transition; }
	public UserAccount getUser() { return user; }
	public ShipmentRiskAlertDeliveryStatus getStatus() { return status; }
	public int getAttemptCount() { return attemptCount; }
	public Instant getNextAttemptAt() { return nextAttemptAt; }
	public Instant getSentAt() { return sentAt; }
	public Instant getLastFailureAt() { return lastFailureAt; }
	public String getLastFailure() { return lastFailure; }
	public String getSuppressionReason() { return suppressionReason; }
	public UUID getClaimToken() { return claimToken; }
	public Instant getClaimUntil() { return claimUntil; }
	public Long getVersion() { return version; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
