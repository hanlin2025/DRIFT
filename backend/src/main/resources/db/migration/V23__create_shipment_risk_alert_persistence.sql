CREATE TABLE shipment_risk_states (
	shipment_id BIGINT PRIMARY KEY REFERENCES shipments(id) ON DELETE CASCADE,
	last_risk_level VARCHAR(16),
	last_explanation TEXT,
	evaluated_at TIMESTAMPTZ,
	baseline_mode VARCHAR(32) NOT NULL,
	last_transition_sequence BIGINT NOT NULL DEFAULT 0,
	version BIGINT NOT NULL DEFAULT 0,
	created_at TIMESTAMPTZ NOT NULL,
	updated_at TIMESTAMPTZ NOT NULL,
	CONSTRAINT shipment_risk_states_level_check
		CHECK (last_risk_level IS NULL OR last_risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
	CONSTRAINT shipment_risk_states_baseline_mode_check
		CHECK (baseline_mode IN ('SILENT_FIRST_VALID', 'ALERTABLE_FIRST_VALID')),
	CONSTRAINT shipment_risk_states_sequence_check CHECK (last_transition_sequence >= 0),
	CONSTRAINT shipment_risk_states_version_check CHECK (version >= 0),
	CONSTRAINT shipment_risk_states_valid_snapshot_check
		CHECK ((last_risk_level IS NULL AND last_explanation IS NULL AND evaluated_at IS NULL)
			OR (last_risk_level IS NOT NULL AND last_explanation IS NOT NULL AND evaluated_at IS NOT NULL))
);

INSERT INTO shipment_risk_states (shipment_id, baseline_mode, last_transition_sequence, version, created_at, updated_at)
SELECT id, 'SILENT_FIRST_VALID', 0, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM shipments;

CREATE TABLE shipment_risk_transitions (
	id BIGSERIAL PRIMARY KEY,
	shipment_id BIGINT NOT NULL REFERENCES shipments(id) ON DELETE RESTRICT,
	sequence BIGINT NOT NULL,
	transition_type VARCHAR(16) NOT NULL,
	previous_risk_level VARCHAR(16),
	risk_level VARCHAR(16) NOT NULL,
	explanation TEXT NOT NULL,
	occurred_at TIMESTAMPTZ NOT NULL,
	created_at TIMESTAMPTZ NOT NULL,
	CONSTRAINT shipment_risk_transitions_shipment_sequence_key UNIQUE (shipment_id, sequence),
	CONSTRAINT shipment_risk_transitions_sequence_check CHECK (sequence > 0),
	CONSTRAINT shipment_risk_transitions_type_check CHECK (transition_type IN ('INITIAL', 'RISE')),
	CONSTRAINT shipment_risk_transitions_previous_level_check
		CHECK (previous_risk_level IS NULL OR previous_risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
	CONSTRAINT shipment_risk_transitions_level_check
		CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
	CONSTRAINT shipment_risk_transitions_type_levels_check CHECK (
		(transition_type = 'INITIAL' AND previous_risk_level IS NULL)
		OR (transition_type = 'RISE' AND previous_risk_level IS NOT NULL)
	)
);

CREATE INDEX shipment_risk_transitions_shipment_occurred_idx
	ON shipment_risk_transitions (shipment_id, occurred_at DESC, sequence DESC);

CREATE TABLE shipment_risk_alert_deliveries (
	id BIGSERIAL PRIMARY KEY,
	transition_id BIGINT NOT NULL REFERENCES shipment_risk_transitions(id) ON DELETE RESTRICT,
	user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
	status VARCHAR(24) NOT NULL,
	attempt_count INTEGER NOT NULL DEFAULT 0,
	next_attempt_at TIMESTAMPTZ,
	sent_at TIMESTAMPTZ,
	last_failure_at TIMESTAMPTZ,
	last_failure TEXT,
	suppression_reason TEXT,
	claim_token UUID,
	claim_until TIMESTAMPTZ,
	version BIGINT NOT NULL DEFAULT 0,
	created_at TIMESTAMPTZ NOT NULL,
	updated_at TIMESTAMPTZ NOT NULL,
	CONSTRAINT shipment_risk_alert_deliveries_transition_user_key UNIQUE (transition_id, user_id),
	CONSTRAINT shipment_risk_alert_deliveries_status_check
		CHECK (status IN ('PENDING', 'SENT', 'RETRYABLE_FAILURE', 'EXHAUSTED', 'SUPPRESSED')),
	CONSTRAINT shipment_risk_alert_deliveries_attempt_count_check CHECK (attempt_count BETWEEN 0 AND 4),
	CONSTRAINT shipment_risk_alert_deliveries_version_check CHECK (version >= 0),
	CONSTRAINT shipment_risk_alert_deliveries_sent_at_check
		CHECK ((status = 'SENT' AND sent_at IS NOT NULL) OR (status <> 'SENT' AND sent_at IS NULL)),
	CONSTRAINT shipment_risk_alert_deliveries_claim_check
		CHECK ((claim_token IS NULL AND claim_until IS NULL) OR (claim_token IS NOT NULL AND claim_until IS NOT NULL)),
	CONSTRAINT shipment_risk_alert_deliveries_retry_time_check
		CHECK ((status = 'RETRYABLE_FAILURE' AND next_attempt_at IS NOT NULL)
			OR (status <> 'RETRYABLE_FAILURE')),
	CONSTRAINT shipment_risk_alert_deliveries_suppression_check
		CHECK (status <> 'SUPPRESSED' OR suppression_reason IS NOT NULL)
);

CREATE INDEX shipment_risk_alert_deliveries_due_idx
	ON shipment_risk_alert_deliveries (next_attempt_at, id)
	WHERE status IN ('PENDING', 'RETRYABLE_FAILURE');

CREATE INDEX shipment_risk_alert_deliveries_user_created_idx
	ON shipment_risk_alert_deliveries (user_id, created_at DESC, id DESC);
