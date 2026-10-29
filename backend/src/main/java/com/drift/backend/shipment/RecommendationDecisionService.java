package com.drift.backend.shipment;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.drift.backend.account.UserAccount;
import com.drift.backend.account.UserAccountRepository;
import com.drift.backend.account.authentication.AuthenticatedUser;
import com.drift.backend.account.exception.SessionEndedException;
import com.drift.backend.company.Company;
import com.drift.backend.shipment.exception.InvalidShipmentRequestException;
import com.drift.backend.shipment.exception.RecommendationDecisionRejectedException;
import com.drift.backend.shipment.exception.ShipmentAccessForbiddenException;
import com.drift.backend.shipment.exception.ShipmentDetailForbiddenException;
import com.drift.backend.shipment.exception.ShipmentNotFoundException;

/**
 * Stores the viewer's current decision against the recommendation from this read.
 * An unavailable recommendation and an archived shipment store nothing. A later decision
 * replaces the current row. The shipment, its risk, and its alerts are left unchanged.
 */
@Service
class RecommendationDecisionService {

	private final ShipmentRepository shipments;
	private final RecommendationDecisionRepository decisions;
	private final ShipmentConnectionRisk connectionRisk;
	private final ShipmentRecommendationImpactSource impacts;
	private final UserAccountRepository users;
	private final ShipmentAccessPolicy access;
	private final Clock clock;

	RecommendationDecisionService(ShipmentRepository shipments, RecommendationDecisionRepository decisions,
			ShipmentConnectionRisk connectionRisk, ShipmentRecommendationImpactSource impacts,
			UserAccountRepository users, ShipmentAccessPolicy access, ObjectProvider<Clock> clocks) {
		this.shipments = shipments;
		this.decisions = decisions;
		this.connectionRisk = connectionRisk;
		this.impacts = impacts;
		this.users = users;
		this.access = access;
		this.clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	@Transactional
	public RecommendationDecisionResponse decide(AuthenticatedUser principal, Long shipmentId,
			RecommendationDecisionRequest request) {
		if (principal == null) {
			throw new SessionEndedException();
		}
		if (request == null || request.outcome() == null) {
			throw new InvalidShipmentRequestException(Map.of("outcome",
					"Outcome must be APPROVED, MODIFIED, or DISMISSED"));
		}
		UserAccount actor = users.findById(principal.id()).orElseThrow(SessionEndedException::new);
		Company company = actor.getCompany();
		if (company == null || !company.isActive()) {
			throw new ShipmentAccessForbiddenException();
		}
		Shipment shipment = shipments.findById(shipmentId).orElseThrow(ShipmentNotFoundException::new);
		if (!access.canDecide(company, shipment)) {
			throw new ShipmentDetailForbiddenException();
		}
		Instant decidedAt = clock.instant();
		RecommendationDecision decision = switch (request.outcome()) {
			case APPROVED -> {
				rejectSelection(request);
				yield approve(shipment, actor, decidedAt);
			}
			case DISMISSED -> {
				rejectSelection(request);
				yield dismiss(shipment, actor, decidedAt);
			}
			case MODIFIED -> modifySelection(shipment, actor, decidedAt, request);
		};
		return RecommendationDecisionResponse.from(decision);
	}

	@Transactional(readOnly = true)
	public RecommendationDecisionResponse current(Long shipmentId) {
		return decisions.findById(shipmentId).map(RecommendationDecisionResponse::from).orElse(null);
	}

	@Transactional
	public RecommendationDecision approve(Shipment shipment, UserAccount actor, Instant decidedAt) {
		return approve(shipment, actor, decidedAt, impacts.current());
	}

	@Transactional
	public RecommendationDecision modify(Shipment shipment, UserAccount actor, Instant decidedAt,
			ShipmentRecommendedResponse.Code selectedResponse) {
		return modify(shipment, actor, decidedAt, selectedResponse, impacts.current());
	}

	@Transactional
	public RecommendationDecision dismiss(Shipment shipment, UserAccount actor, Instant decidedAt) {
		return dismiss(shipment, actor, decidedAt, impacts.current());
	}

	@Transactional
	RecommendationDecision approve(Shipment shipment, UserAccount actor, Instant decidedAt,
			ShipmentRecommendationImpact impact) {
		return store(shipment, actor, decidedAt, RecommendationDecisionOutcome.APPROVED, null, impact);
	}

	@Transactional
	RecommendationDecision modify(Shipment shipment, UserAccount actor, Instant decidedAt,
			ShipmentRecommendedResponse.Code selectedResponse, ShipmentRecommendationImpact impact) {
		return store(shipment, actor, decidedAt, RecommendationDecisionOutcome.MODIFIED, selectedResponse, impact);
	}

	@Transactional
	RecommendationDecision dismiss(Shipment shipment, UserAccount actor, Instant decidedAt,
			ShipmentRecommendationImpact impact) {
		return store(shipment, actor, decidedAt, RecommendationDecisionOutcome.DISMISSED, null, impact);
	}

	private RecommendationDecision store(Shipment shipment, UserAccount actor, Instant decidedAt,
			RecommendationDecisionOutcome outcome, ShipmentRecommendedResponse.Code selected,
			ShipmentRecommendationImpact impact) {
		if (shipment == null || shipment.getId() == null) {
			throw new ShipmentNotFoundException();
		}
		Shipment current = shipments.findById(shipment.getId()).orElseThrow(ShipmentNotFoundException::new);
		if (current.getStatus() == ShipmentStatus.ARCHIVED) {
			throw new RecommendationDecisionRejectedException(RecommendationDecisionRejectedException.ARCHIVED);
		}
		ShipmentRecommendation recommendation = ShipmentRecommendation.of(connectionRisk.assess(current).risk(), impact);
		if (!recommendation.available()) {
			throw new RecommendationDecisionRejectedException(RecommendationDecisionRejectedException.UNAVAILABLE);
		}
		String name = recommendation.response().label();
		String wording = recommendation.response().wording();
		String selectedName = null;
		String selectedWording = null;
		if (selectionRequired(outcome)) {
			if (selected == null) {
				throw new IllegalArgumentException("A modification needs the selected response");
			}
			if (selected == recommendation.response().code()) {
				throw new IllegalArgumentException("A modification selects a different response");
			}
			selectedName = selected.label();
			selectedWording = selected.wording();
		} else if (selected != null) {
			throw new IllegalArgumentException("A selected response is stored only for a modification");
		}
		RecommendationDecision existing = decisions.findById(current.getId()).orElse(null);
		if (existing == null) {
			return decisions.saveAndFlush(create(current, outcome, name, wording, selectedName, selectedWording, actor,
					decidedAt));
		}
		existing.replace(outcome, name, wording, selectedName, selectedWording, actor, decidedAt);
		return decisions.saveAndFlush(existing);
	}

	private RecommendationDecision modifySelection(Shipment shipment, UserAccount actor, Instant decidedAt,
			RecommendationDecisionRequest request) {
		if (request.selectedResponse() == null) {
			throw new InvalidShipmentRequestException(Map.of("selectedResponse",
					"A modification needs the selected response"));
		}
		try {
			return modify(shipment, actor, decidedAt, request.selectedResponse());
		} catch (IllegalArgumentException ex) {
			throw new InvalidShipmentRequestException(Map.of("selectedResponse", ex.getMessage()));
		}
	}

	private static void rejectSelection(RecommendationDecisionRequest request) {
		if (request.selectedResponse() != null) {
			throw new InvalidShipmentRequestException(Map.of("selectedResponse",
					"A selected response is stored only for a modification"));
		}
	}

	private static boolean selectionRequired(RecommendationDecisionOutcome outcome) {
		return switch (outcome) {
			case APPROVED, DISMISSED -> false;
			case MODIFIED -> true;
		};
	}

	private static RecommendationDecision create(Shipment shipment, RecommendationDecisionOutcome outcome, String name,
			String wording, String selectedName, String selectedWording, UserAccount actor, Instant decidedAt) {
		return switch (outcome) {
			case APPROVED -> RecommendationDecision.approve(shipment, name, wording, actor, decidedAt);
			case DISMISSED -> RecommendationDecision.dismiss(shipment, name, wording, actor, decidedAt);
			case MODIFIED -> RecommendationDecision.modify(shipment, name, wording, selectedName, selectedWording, actor,
					decidedAt);
		};
	}
}
