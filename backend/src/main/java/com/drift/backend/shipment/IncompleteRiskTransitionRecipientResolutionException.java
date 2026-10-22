package com.drift.backend.shipment;

/** A committed transition without a completion marker cannot safely be enrolled retrospectively. */
public class IncompleteRiskTransitionRecipientResolutionException extends IllegalStateException {

	public IncompleteRiskTransitionRecipientResolutionException(Long transitionId) {
		super("Risk transition " + transitionId + " was committed without recipient resolution");
	}
}
