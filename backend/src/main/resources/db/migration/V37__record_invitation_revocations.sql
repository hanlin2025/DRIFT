CREATE TABLE invitation_revocations (
    id BIGSERIAL PRIMARY KEY,
    invitation_id BIGINT NOT NULL REFERENCES invitations (id),
    operator_user_id BIGINT NOT NULL REFERENCES users (id),
    organisation_id BIGINT NOT NULL REFERENCES companies (id),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT invitation_revocations_one_per_invitation UNIQUE (invitation_id)
);

CREATE INDEX invitation_revocations_recorded_at_idx
    ON invitation_revocations (recorded_at DESC, id DESC);
