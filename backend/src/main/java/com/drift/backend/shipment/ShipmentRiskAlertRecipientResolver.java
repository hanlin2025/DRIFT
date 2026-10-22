package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.RiskNotificationLevel;
import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.company.Company;

/** Resolves recipients only while a newly created transition is still in its creating transaction. */
@Service
public class ShipmentRiskAlertRecipientResolver {

	private final UserAccountRepository users;
	private final ShipmentRiskAlertDeliveryRepository deliveries;
	private final ShipmentRiskTransitionRepository transitions;
	private final ShipmentAccessPolicy access;
	private final Clock clock;

	public ShipmentRiskAlertRecipientResolver(UserAccountRepository users, ShipmentRiskAlertDeliveryRepository deliveries,
			ShipmentRiskTransitionRepository transitions, ShipmentAccessPolicy access, ObjectProvider<Clock> clocks) {
		this.users = users;
		this.deliveries = deliveries;
		this.transitions = transitions;
		this.access = access;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	/** Called only for a transition created in the current evaluator transaction. */
	public void resolveNewTransition(ShipmentRiskTransition transition) {
		if (transition == null || transition.getRecipientResolutionCompletedAt() != null) {
			throw new IllegalArgumentException("An unresolved new transition is required");
		}
		Shipment shipment = transition.getShipment();
		if (shipment.getStatus() != ShipmentStatus.ACTIVE) {
			transition.markRecipientResolutionCompleted(Instant.now(clock));
			return;
		}

		RiskNotificationLevel riskLevel = RiskNotificationLevel.valueOf(transition.getRiskLevel().name());
		Instant now = Instant.now(clock);
		for (UserAccount account : users.findActiveUsersForCompanies(visibleCompanyIds(shipment))) {
			Company company = account.getCompany();
			if (company != null && company.isActive() && access.canView(company, shipment)
					&& usableEmail(account) && account.notifiesFor(riskLevel)) {
				deliveries.save(new ShipmentRiskAlertDelivery(transition, account, now));
			}
		}
		transition.markRecipientResolutionCompleted(now);
	}

	/** Completed transitions return their immutable recipient snapshot; incomplete ones fail closed. */
	@Transactional
	public List<ShipmentRiskAlertDelivery> deliveriesForCompletedTransition(Long transitionId) {
		ShipmentRiskTransition transition = transitions.findByIdForRecipientResolution(transitionId)
				.orElseThrow(() -> new IllegalArgumentException("Risk transition was not found"));
		if (transition.getRecipientResolutionCompletedAt() == null) {
			throw new IncompleteRiskTransitionRecipientResolutionException(transitionId);
		}
		return deliveries.findByTransitionIdOrderByIdAsc(transitionId);
	}

	private static Set<Long> visibleCompanyIds(Shipment shipment) {
		Set<Long> ids = new LinkedHashSet<>();
		ids.add(shipment.getCompany().getId());
		if (shipment.getImporterCompany() != null) {
			ids.add(shipment.getImporterCompany().getId());
		}
		return ids;
	}

	private static boolean usableEmail(UserAccount account) {
		return account.getEmail() != null && !account.getEmail().isBlank();
	}
}
