package com.drift.backend.shipment.exception;

public class RecommendationDecisionRejectedException extends RuntimeException {

	public static final String UNAVAILABLE = "An unavailable recommendation is not stored.";
	public static final String ARCHIVED = "An archived shipment does not accept a new decision.";

	public RecommendationDecisionRejectedException(String message) {
		super(message);
	}
}
