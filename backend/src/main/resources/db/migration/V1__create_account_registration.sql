CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    full_name VARCHAR(200) NOT NULL,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT users_email_key UNIQUE (email),
    CONSTRAINT users_role_check CHECK (role IN ('IMPORTER', 'FREIGHT_FORWARDER'))
);

CREATE TABLE invitations (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    consumed_at TIMESTAMPTZ,
    CONSTRAINT invitations_role_check CHECK (role IN ('IMPORTER', 'FREIGHT_FORWARDER')),
    CONSTRAINT invitations_status_check CHECK (status IN ('PENDING', 'CONSUMED'))
);

CREATE UNIQUE INDEX invitations_one_pending_email
    ON invitations (LOWER(email))
    WHERE status = 'PENDING';
