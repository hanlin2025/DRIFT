package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.AccountStatus;
import com.drift.backend.account.RiskNotificationLevel;
import com.drift.backend.account.UserAccount;
import com.drift.backend.company.Company;

/**
 * Performs the short database transactions around an SMTP attempt. Network I/O belongs in
 * {@link ShipmentRiskAlertDeliveryService}, after {@link #claimDueDelivery(Long, boolean)} commits.
 */
@Service
class ShipmentRiskAlertDeliveryStore {

	private final ShipmentRiskAlertDeliveryRepository deliveries;
	private final ShipmentAccessPolicy access;
	private final ShipmentRiskAlertMailProperties mail;
	private final ShipmentRiskAlertEmailComposer composer;
	private final ShipmentRiskAlertRetryPolicy retryPolicy;
	private final Clock clock;
	private final long maximumSmtpAttemptMillis;

	ShipmentRiskAlertDeliveryStore(ShipmentRiskAlertDeliveryRepository deliveries, ShipmentAccessPolicy access,
			ShipmentRiskAlertMailProperties mail, ShipmentRiskAlertEmailComposer composer,
			ShipmentRiskAlertRetryPolicy retryPolicy, ObjectProvider<Clock> clocks,
			@Value("${spring.mail.properties.mail.smtp.connectiontimeout:0}") long connectionTimeoutMillis,
			@Value("${spring.mail.properties.mail.smtp.timeout:0}") long readTimeoutMillis,
			@Value("${spring.mail.properties.mail.smtp.writetimeout:0}") long writeTimeoutMillis) {
		this.deliveries = deliveries;
		this.access = access;
		this.mail = mail;
		this.composer = composer;
		this.retryPolicy = retryPolicy;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
		this.maximumSmtpAttemptMillis = boundedAttemptDuration(connectionTimeoutMillis, readTimeoutMillis, writeTimeoutMillis);
	}

	/**
	 * Claims and records exactly one due SMTP attempt. The increment commits before SMTP begins.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	ClaimResult claimDueDelivery(Long deliveryId, boolean javaMailSenderAvailable) {
		if (deliveryId == null) {
			throw new IllegalArgumentException("Delivery identifier is required");
		}
		ShipmentRiskAlertDelivery delivery = deliveries.findByIdForDeliveryProcessing(deliveryId).orElse(null);
		if (delivery == null || (delivery.getStatus() != ShipmentRiskAlertDeliveryStatus.PENDING
				&& delivery.getStatus() != ShipmentRiskAlertDeliveryStatus.RETRYABLE_FAILURE)) {
			return ClaimResult.notPending();
		}

		Instant now = Instant.now(clock);
		UUID token = UUID.randomUUID();
		Duration lease = mail.getClaimLease();
		if (lease == null || lease.isZero() || lease.isNegative()
				|| !delivery.claim(token, now.plus(lease), now)) {
			return ClaimResult.notPending();
		}
		if (delivery.getAttemptCount() >= 4) {
			delivery.markFailure(token, ShipmentRiskAlertDeliveryStatus.EXHAUSTED,
					"Delivery claim expired after the maximum SMTP attempts", null, now);
			return ClaimResult.exhausted();
		}

		String suppression = mail.unavailableReason(javaMailSenderAvailable);
		if (suppression == null && mail.getClaimLease().toMillis() <= maximumSmtpAttemptMillis) {
			suppression = "Risk alert claim lease is shorter than configured SMTP timeouts";
		}
		if (suppression == null) {
			suppression = ineligibilityReason(delivery);
		}
		if (suppression != null) {
			delivery.suppress(token, suppression, now);
			return ClaimResult.suppressed();
		}

		try {
			SimpleMailMessage message = composer.compose(delivery, mail);
			delivery.recordAttempt(token, now);
			return ClaimResult.claimed(token, message);
		} catch (RuntimeException ex) {
			delivery.suppress(token, "Risk alert email could not be composed", now);
			return ClaimResult.suppressed();
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	boolean markSent(Long deliveryId, UUID token) {
		ShipmentRiskAlertDelivery delivery = lockedDelivery(deliveryId);
		return delivery != null && delivery.markSent(token, Instant.now(clock));
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	ShipmentRiskAlertDeliveryStatus markFailure(Long deliveryId, UUID token, String sanitizedFailure) {
		ShipmentRiskAlertDelivery delivery = lockedDelivery(deliveryId);
		if (delivery == null) {
			return null;
		}
		Instant now = Instant.now(clock);
		ShipmentRiskAlertRetryPolicy.RetryDecision decision = retryPolicy.afterFailure(delivery.getAttemptCount());
		Instant nextAttemptAt = decision.delay() == null ? null : now.plus(decision.delay());
		return delivery.markFailure(token, decision.status(), sanitizedFailure, nextAttemptAt, now)
				? decision.status() : null;
	}

	private ShipmentRiskAlertDelivery lockedDelivery(Long deliveryId) {
		return deliveryId == null ? null : deliveries.findByIdForDeliveryProcessing(deliveryId).orElse(null);
	}

	private static long boundedAttemptDuration(long connectionTimeoutMillis, long readTimeoutMillis, long writeTimeoutMillis) {
		if (connectionTimeoutMillis <= 0 || readTimeoutMillis <= 0 || writeTimeoutMillis <= 0) {
			return Long.MAX_VALUE;
		}
		try {
			return Math.addExact(Math.addExact(connectionTimeoutMillis, readTimeoutMillis), writeTimeoutMillis);
		} catch (ArithmeticException ex) {
			return Long.MAX_VALUE;
		}
	}

	private String ineligibilityReason(ShipmentRiskAlertDelivery delivery) {
		ShipmentRiskTransition transition = delivery.getTransition();
		Shipment shipment = transition.getShipment();
		UserAccount recipient = delivery.getUser();
		if (shipment.getStatus() != ShipmentStatus.ACTIVE) {
			return "Shipment is archived";
		}
		if (recipient.getStatus() != AccountStatus.ACTIVE) {
			return "Recipient account is not active";
		}
		Company company = recipient.getCompany();
		if (company == null || !company.isActive()) {
			return "Recipient company is not active";
		}
		if (!access.canView(company, shipment)) {
			return "Recipient no longer has shipment access";
		}
		if (recipient.getEmail() == null || recipient.getEmail().isBlank()) {
			return "Recipient email address is unavailable";
		}
		try {
			RiskNotificationLevel level = RiskNotificationLevel.valueOf(transition.getRiskLevel().name());
			if (!recipient.notifiesFor(level)) {
				return "Recipient notification preference no longer includes this risk level";
			}
		} catch (IllegalArgumentException ex) {
			return "Transition risk level is not supported for notifications";
		}
		return null;
	}

	record ClaimResult(Kind kind, UUID token, SimpleMailMessage message) {
		enum Kind { CLAIMED, EXHAUSTED, SUPPRESSED, NOT_PENDING }

		static ClaimResult claimed(UUID token, SimpleMailMessage message) {
			return new ClaimResult(Kind.CLAIMED, token, message);
		}

		static ClaimResult suppressed() {
			return new ClaimResult(Kind.SUPPRESSED, null, null);
		}

		static ClaimResult exhausted() {
			return new ClaimResult(Kind.EXHAUSTED, null, null);
		}

		static ClaimResult notPending() {
			return new ClaimResult(Kind.NOT_PENDING, null, null);
		}
	}
}
