CREATE TABLE password_reset_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);

CREATE UNIQUE INDEX password_reset_tokens_token_hash_key
    ON password_reset_tokens (token_hash);

CREATE INDEX password_reset_tokens_user_id
    ON password_reset_tokens (user_id);
