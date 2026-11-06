package com.drift.backend.shipment;

import java.time.Instant;

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

/** One accepted recommendation decision. Rows are inserted and read, not updated or deleted. */
@Entity
@Table(name = "recommendation_decision_history")
public class RecommendationDecisionHistory {

	static final int NAME_MAX_LENGTH = 100;
	static final int ACTOR_NAME_MAX_LENGTH = 200;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
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

	@Column(name = "decided_by_name", nullable = false, length = ACTOR_NAME_MAX_LENGTH)
	private String decidedByName;

	@Column(name = "decided_at", nullable = false)
	private Instant decidedAt;

	protected RecommendationDecisionHistory() {
	}

	private RecommendationDecisionHistory(Shipment shipment, RecommendationDecisionOutcome outcome,
			String recommendationName, String recommendationWording, String selectedResponseName,
			String selectedResponseWording, UserAccount decidedBy, String decidedByName, Instant decidedAt) {
		if (shipment == null || shipment.getId() == null) {
			throw new IllegalArgumentException("Shipment is required");
		}
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
		String actorName = requiredText(decidedByName, "Actor name", ACTOR_NAME_MAX_LENGTH);
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
		this.shipment = shipment;
		this.outcome = outcome;
		this.recommendationName = name;
		this.recommendationWording = wording;
		this.selectedResponseName = selectedName;
		this.selectedResponseWording = selectedWording;
		this.decidedBy = decidedBy;
		this.decidedByName = actorName;
		this.decidedAt = decidedAt;
	}

	public static RecommendationDecisionHistory record(Shipment shipment, RecommendationDecisionOutcome outcome,
			String recommendationName, String recommendationWording, String selectedResponseName,
			String selectedResponseWording, UserAccount decidedBy, String decidedByName, Instant decidedAt) {
		return new RecommendationDecisionHistory(shipment, outcome, recommendationName, recommendationWording,
				selectedResponseName, selectedResponseWording, decidedBy, decidedByName, decidedAt);
	}

	public Long getId() {
		return id;
	}

	public Shipment getShipment() {
		return shipment;
	}

	public RecommendationDecisionOutcome getOutcome() {
		return outcome;
	}

	public String getRecommendationName() {
		return recommendationName;
	}

	public String getRecommendationWording() {
		return recommendationWording;
	}

	public String getSelectedResponseName() {
		return selectedResponseName;
	}

	public String getSelectedResponseWording() {
		return selectedResponseWording;
	}

	public UserAccount getDecidedBy() {
		return decidedBy;
	}

	public String getDecidedByName() {
		return decidedByName;
	}

	public Instant getDecidedAt() {
		return decidedAt;
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
