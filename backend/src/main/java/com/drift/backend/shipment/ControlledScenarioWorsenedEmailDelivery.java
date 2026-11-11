package com.drift.backend.shipment;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Sends only the controlled-demo event, independently of shipment risk-alert delivery. */
@Component
class ControlledScenarioWorsenedEmailDelivery {
	private static final Logger log = LoggerFactory.getLogger(ControlledScenarioWorsenedEmailDelivery.class);

	private final ControlledScenarioSessionService sessions;
	private final ControlledScenarioEmailProperties properties;
	private final ControlledScenarioWorsenedEmailComposer composer;
	private final ObjectProvider<JavaMailSender> mailSenders;
	private final String from;
	private final String frontendBaseUrl;

	ControlledScenarioWorsenedEmailDelivery(ControlledScenarioSessionService sessions,
			ControlledScenarioEmailProperties properties, ControlledScenarioWorsenedEmailComposer composer,
			ObjectProvider<JavaMailSender> mailSenders,
			@Value("${app.mail.from}") String from,
			@Value("${app.frontend-base-url}") String frontendBaseUrl) {
		this.sessions = sessions;
		this.properties = properties;
		this.composer = composer;
		this.mailSenders = mailSenders;
		this.from = from;
		this.frontendBaseUrl = frontendBaseUrl;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void onWorsened(ControlledScenarioWorsenedEvent event) {
		if (!sessions.claimEmailSubmission(event.sessionId(), event.epochId(), event.revision())) return;
		if (!properties.isEnabled()) {
			sessions.recordEmailSubmissionOutcome(event.sessionId(), event.epochId(), event.revision(),
					ControlledScenarioEmailStatus.DISABLED);
			return;
		}
		List<String> recipients = properties.approvedRecipients();
		if (recipients.isEmpty()) {
			sessions.recordEmailSubmissionOutcome(event.sessionId(), event.epochId(), event.revision(),
					ControlledScenarioEmailStatus.NO_APPROVED_RECIPIENTS);
			return;
		}
		JavaMailSender sender = mailSenders.getIfAvailable();
		if (sender == null) {
			log.warn("Controlled synthetic email failed: no JavaMailSender is configured (session {}, revision {}).",
					event.sessionId(), event.revision());
			sessions.recordEmailSubmissionOutcome(event.sessionId(), event.epochId(), event.revision(),
					ControlledScenarioEmailStatus.FAILED);
			return;
		}
		int sent = 0;
		int recipientIndex = 0;
		for (String recipient : recipients) {
			recipientIndex++;
			try {
				SimpleMailMessage message = composer.compose(event.snapshot(), event.scenarioShipmentKey(), from, recipient, frontendBaseUrl);
				sender.send(message);
				sent++;
			} catch (RuntimeException exception) {
				log.warn("Controlled synthetic email submission failed (session {}, revision {}, recipient index {}, exception {}).",
						event.sessionId(), event.revision(), recipientIndex, exception.getClass().getSimpleName());
			}
		}
		ControlledScenarioEmailStatus outcome = sent == recipients.size()
				? ControlledScenarioEmailStatus.SUBMITTED
				: sent == 0 ? ControlledScenarioEmailStatus.FAILED : ControlledScenarioEmailStatus.PARTIAL_FAILURE;
		sessions.recordEmailSubmissionOutcome(event.sessionId(), event.epochId(), event.revision(), outcome);
	}
}
