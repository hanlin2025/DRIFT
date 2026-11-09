CREATE TABLE installation_setup (
    id SMALLINT PRIMARY KEY,
    administrator_user_id BIGINT NOT NULL REFERENCES users (id),
    organisation_id BIGINT NOT NULL REFERENCES companies (id),
    completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT installation_setup_singleton CHECK (id = 1)
);
