CREATE TABLE shipment_risk_alert_activation (
	id SMALLINT PRIMARY KEY CHECK (id = 1),
	generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
	activated_at TIMESTAMPTZ,
	version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
	created_at TIMESTAMPTZ NOT NULL,
	updated_at TIMESTAMPTZ NOT NULL
);

INSERT INTO shipment_risk_alert_activation (id, generation, activated_at, version, created_at, updated_at)
VALUES (1, 0, NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

ALTER TABLE shipment_risk_states
	ADD COLUMN activation_generation BIGINT NOT NULL DEFAULT 0
	CHECK (activation_generation >= 0);

ALTER TABLE shipment_risk_transitions
	ADD COLUMN activation_generation BIGINT NOT NULL DEFAULT 0
	CHECK (activation_generation >= 0);

CREATE INDEX shipment_risk_transitions_activation_generation_idx
	ON shipment_risk_transitions (activation_generation, id);
