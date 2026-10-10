CREATE TABLE roles (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    CONSTRAINT roles_code_key UNIQUE (code),
    CONSTRAINT roles_code_check CHECK (code IN ('IMPORTER', 'FREIGHT_FORWARDER', 'ADMIN'))
);

INSERT INTO roles (code) VALUES
    ('IMPORTER'),
    ('FREIGHT_FORWARDER'),
    ('ADMIN');

ALTER TABLE users ADD COLUMN role_id BIGINT;

UPDATE users AS account
SET role_id = role.id
FROM roles AS role
WHERE role.code = account.role;

ALTER TABLE users
    ALTER COLUMN role_id SET NOT NULL,
    ADD CONSTRAINT users_role_id_fkey FOREIGN KEY (role_id) REFERENCES roles (id);

ALTER TABLE users DROP CONSTRAINT users_role_check;
ALTER TABLE users DROP COLUMN role;
