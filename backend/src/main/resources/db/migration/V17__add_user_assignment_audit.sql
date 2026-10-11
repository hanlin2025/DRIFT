CREATE TABLE user_assignment_audits (
    id BIGSERIAL PRIMARY KEY,
    operator_user_id BIGINT NOT NULL REFERENCES users (id),
    target_user_id BIGINT NOT NULL REFERENCES users (id),
    previous_role VARCHAR(32) NOT NULL,
    assigned_role VARCHAR(32) NOT NULL,
    previous_organisation_id BIGINT,
    previous_organisation_code VARCHAR(50),
    previous_organisation_name VARCHAR(200),
    organisation_id BIGINT NOT NULL REFERENCES companies (id),
    organisation_code VARCHAR(50) NOT NULL,
    organisation_name VARCHAR(200) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX user_assignment_audits_recorded_at_idx
    ON user_assignment_audits (recorded_at DESC, id DESC);
