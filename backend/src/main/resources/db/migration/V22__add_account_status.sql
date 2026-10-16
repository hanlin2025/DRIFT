ALTER TABLE users
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT users_status_check
        CHECK (status IN ('ACTIVE', 'REMOVED'));

CREATE TABLE account_status_audits (
    id BIGSERIAL PRIMARY KEY,
    operator_user_id BIGINT NOT NULL REFERENCES users (id),
    target_user_id BIGINT NOT NULL REFERENCES users (id),
    organisation_id BIGINT NOT NULL REFERENCES companies (id),
    previous_status VARCHAR(16) NOT NULL,
    new_status VARCHAR(16) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT account_status_audits_previous_status_check
        CHECK (previous_status IN ('ACTIVE', 'REMOVED')),
    CONSTRAINT account_status_audits_new_status_check
        CHECK (new_status IN ('ACTIVE', 'REMOVED'))
);

CREATE INDEX account_status_audits_recorded_at_idx
    ON account_status_audits (recorded_at DESC, id DESC);
