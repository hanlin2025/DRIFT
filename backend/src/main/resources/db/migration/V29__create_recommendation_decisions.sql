-- One current recommendation decision per shipment. Earlier decisions are not kept.

CREATE TABLE recommendation_decisions (
	shipment_id BIGINT PRIMARY KEY REFERENCES shipments(id) ON DELETE CASCADE,
	outcome VARCHAR(16) NOT NULL,
	recommendation_name VARCHAR(100) NOT NULL,
	recommendation_wording TEXT NOT NULL,
	selected_response_name VARCHAR(100),
	selected_response_wording TEXT,
	decided_by_user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
	decided_at TIMESTAMPTZ NOT NULL,
	CONSTRAINT recommendation_decisions_outcome_check
		CHECK (outcome IN ('APPROVED', 'MODIFIED', 'DISMISSED')),
	CONSTRAINT recommendation_decisions_recommendation_text_check
		CHECK (char_length(btrim(recommendation_name)) > 0
			AND char_length(btrim(recommendation_wording)) > 0),
	CONSTRAINT recommendation_decisions_selected_check
		CHECK (
			(outcome IN ('APPROVED', 'DISMISSED')
				AND selected_response_name IS NULL
				AND selected_response_wording IS NULL)
			OR (outcome = 'MODIFIED'
				AND selected_response_name IS NOT NULL
				AND selected_response_wording IS NOT NULL
				AND char_length(btrim(selected_response_name)) > 0
				AND char_length(btrim(selected_response_wording)) > 0
				AND btrim(selected_response_name) <> btrim(recommendation_name))
		)
);

CREATE INDEX recommendation_decisions_decided_by_user_idx
	ON recommendation_decisions (decided_by_user_id);
