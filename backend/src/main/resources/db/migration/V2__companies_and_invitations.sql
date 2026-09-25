CREATE TABLE companies (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

INSERT INTO companies (code, name) VALUES
    ('HARBOURLINE_DEMO', 'Harbourline Logistics (Demo)'),
    ('STRAITS_FRESH_DEMO', 'Straits Fresh Imports (Demo)');

-- Legacy accounts remain unassigned until company membership is verified.
ALTER TABLE users ADD COLUMN company_id BIGINT REFERENCES companies(id);
CREATE UNIQUE INDEX users_email_lower_key ON users (LOWER(email));

CREATE TABLE invitations (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(320) NOT NULL CHECK (email = LOWER(TRIM(email))),
    company_id BIGINT NOT NULL REFERENCES companies(id),
    role VARCHAR(32) NOT NULL CHECK (role IN ('IMPORTER', 'FREIGHT_FORWARDER')),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONSUMED', 'REVOKED')),
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT invitations_consumption_check CHECK (
        (status = 'CONSUMED' AND consumed_at IS NOT NULL) OR
        (status <> 'CONSUMED' AND consumed_at IS NULL)
    )
);
CREATE UNIQUE INDEX invitations_one_pending_email ON invitations (email) WHERE status = 'PENDING';
