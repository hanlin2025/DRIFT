package com.drift.backend.shipment;

import java.time.Instant;

import com.drift.backend.account.UserAccount;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/** The one current viewer decision for a shipment. A later decision replaces this row. */
@Entity
@Table(name = "recommendation_decisions")
public class RecommendationDecision {

	static final int NAME_MAX_LENGTH = 100;

	@Id
	private Long shipmentId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "shipment_id", nullable = false)
	private Shipment shipment;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private RecommendationDecisionOutcome outcome;

	@Column(name = "recommendation_name", nullable = false, length = NAME_MAX_LENGTH)
	private String recommendationName;

	@Column(name = "recommendation_wording", nullable = false)
	private String recommendationWording;

	@Column(name = "selected_response_name", length = NAME_MAX_LENGTH)
	private String selectedResponseName;

	@Column(name = "selected_response_wording")
	private String selectedResponseWording;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "decided_by_user_id", nullable = false)
	private UserAccount decidedBy;

	@Column(name = "decided_at", nullable = false)
	private Instant decidedAt;

	protected RecommendationDecision() {
	}

	private RecommendationDecision(Shipment shipment, RecommendationDecisionOutcome outcome, String recommendationName,
			String recommendationWording, String selectedResponseName, String selectedResponseWording,
			UserAccount decidedBy, Instant decidedAt) {
		if (shipment == null) {
			throw new IllegalArgumentException("Shipment is required");
		}
		this.shipment = shipment;
		apply(outcome, recommendationName, recommendationWording, selectedResponseName, selectedResponseWording,
				decidedBy, decidedAt);
	}

	public static RecommendationDecision approve(Shipment shipment, String recommendationName,
			String recommendationWording, UserAccount decidedBy, Instant decidedAt) {
		return new RecommendationDecision(shipment, RecommendationDecisionOutcome.APPROVED, recommendationName,
				recommendationWording, null, null, decidedBy, decidedAt);
	}

	public static RecommendationDecision modify(Shipment shipment, String recommendationName,
			String recommendationWording, String selectedResponseName, String selectedResponseWording,
			UserAccount decidedBy, Instant decidedAt) {
		return new RecommendationDecision(shipment, RecommendationDecisionOutcome.MODIFIED, recommendationName,
				recommendationWording, selectedResponseName, selectedResponseWording, decidedBy, decidedAt);
	}

	public static RecommendationDecision dismiss(Shipment shipment, String recommendationName,
			String recommendationWording, UserAccount decidedBy, Instant decidedAt) {
		return new RecommendationDecision(shipment, RecommendationDecisionOutcome.DISMISSED, recommendationName,
				recommendationWording, null, null, decidedBy, decidedAt);
	}

	public void replace(RecommendationDecisionOutcome outcome, String recommendationName, String recommendationWording,
			String selectedResponseName, String selectedResponseWording, UserAccount decidedBy, Instant decidedAt) {
		apply(outcome, recommendationName, recommendationWording, selectedResponseName, selectedResponseWording,
				decidedBy, decidedAt);
	}

	public Long getShipmentId() { return shipmentId; }
	public Shipment getShipment() { return shipment; }
	public RecommendationDecisionOutcome getOutcome() { return outcome; }
	public String getRecommendationName() { return recommendationName; }
	public String getRecommendationWording() { return recommendationWording; }
	public String getSelectedResponseName() { return selectedResponseName; }
	public String getSelectedResponseWording() { return selectedResponseWording; }
	public UserAccount getDecidedBy() { return decidedBy; }
	public Instant getDecidedAt() { return decidedAt; }

	private void apply(RecommendationDecisionOutcome outcome, String recommendationName, String recommendationWording,
			String selectedResponseName, String selectedResponseWording, UserAccount decidedBy, Instant decidedAt) {
		if (outcome == null) {
			throw new IllegalArgumentException("Outcome is required");
		}
		if (decidedBy == null) {
			throw new IllegalArgumentException("Actor is required");
		}
		if (decidedAt == null) {
			throw new IllegalArgumentException("Decision time is required");
		}
		String name = requiredText(recommendationName, "Recommendation name", NAME_MAX_LENGTH);
		String wording = requiredText(recommendationWording, "Recommendation wording", 0);
		String selectedName = absentOrText(selectedResponseName, "Selected response name", NAME_MAX_LENGTH);
		String selectedWording = absentOrText(selectedResponseWording, "Selected response wording", 0);
		if (selectionRequired(outcome)) {
			if (selectedName == null || selectedWording == null) {
				throw new IllegalArgumentException("A modification needs the selected response");
			}
			if (selectedName.equals(name)) {
				throw new IllegalArgumentException("A modification selects a different response");
			}
		} else if (selectedName != null || selectedWording != null) {
			throw new IllegalArgumentException("A selected response is stored only for a modification");
		}
		this.outcome = outcome;
		this.recommendationName = name;
		this.recommendationWording = wording;
		this.selectedResponseName = selectedName;
		this.selectedResponseWording = selectedWording;
		this.decidedBy = decidedBy;
		this.decidedAt = decidedAt;
	}

	private static boolean selectionRequired(RecommendationDecisionOutcome outcome) {
		return switch (outcome) {
			case APPROVED, DISMISSED -> false;
			case MODIFIED -> true;
		};
	}

	private static String requiredText(String value, String label, int maxLength) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(label + " is required");
		}
		return limited(value.trim(), label, maxLength);
	}

	private static String absentOrText(String value, String label, int maxLength) {
		if (value == null) {
			return null;
		}
		if (value.isBlank()) {
			throw new IllegalArgumentException(label + " cannot be blank");
		}
		return limited(value.trim(), label, maxLength);
	}

	private static String limited(String value, String label, int maxLength) {
		if (maxLength > 0 && value.length() > maxLength) {
			throw new IllegalArgumentException(label + " is too long");
		}
		return value;
	}
}
