-- CDG-361: self-service company signup with a first administrator awaiting email verification.
-- V39 is used by the maritime snapshot index (CDG-358). users.created_at already exists (V1) for ageing pending signups.

-- 'PENDING_VERIFICATION' is 20 characters, longer than the VARCHAR(16) added in V22.
ALTER TABLE users ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE users DROP CONSTRAINT users_status_check;
ALTER TABLE users
    ADD CONSTRAINT users_status_check
        CHECK (status IN ('ACTIVE', 'REMOVED', 'PENDING_VERIFICATION'));

CREATE TABLE email_verification_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX email_verification_tokens_token_hash_key ON email_verification_tokens (token_hash);
CREATE INDEX email_verification_tokens_user_id ON email_verification_tokens (user_id);

CREATE TABLE company_signup_audits (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    company_id BIGINT NOT NULL REFERENCES companies(id),
    event VARCHAR(32) NOT NULL CHECK (event IN ('REGISTERED', 'EMAIL_VERIFIED', 'RESUBMITTED')),
    recorded_at TIMESTAMPTZ NOT NULL
);
