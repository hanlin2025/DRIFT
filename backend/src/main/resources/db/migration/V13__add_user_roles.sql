CREATE TABLE roles (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(32) NOT NULL,
    CONSTRAINT roles_code_key UNIQUE (code),
    CONSTRAINT roles_code_check CHECK (code IN ('IMPORTER', 'FREIGHT_FORWARDER', 'ADMIN', 'LOGISTICS_MANAGER'))
);

INSERT INTO roles (code) VALUES
    ('IMPORTER'),
    ('FREIGHT_FORWARDER'),
    ('ADMIN'),
    ('LOGISTICS_MANAGER');

ALTER TABLE users ADD COLUMN role_id BIGINT;

UPDATE users AS account
SET role_id = role.id
FROM roles AS role
WHERE role.code = account.role;

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_role_check;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_admin_has_company_check;
DROP INDEX IF EXISTS users_one_admin_per_company;

ALTER TABLE users DROP COLUMN role;

ALTER TABLE users
    ALTER COLUMN role_id SET NOT NULL,
    ADD CONSTRAINT users_role_id_fkey FOREIGN KEY (role_id) REFERENCES roles (id);

CREATE FUNCTION users_admin_role_rules() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    role_code VARCHAR(32);
BEGIN
    SELECT code INTO role_code FROM roles WHERE id = NEW.role_id;
    IF role_code = 'ADMIN' AND NEW.company_id IS NULL THEN
        RAISE EXCEPTION 'An admin must belong to a company';
    END IF;
    IF role_code = 'ADMIN' AND EXISTS (
        SELECT 1 FROM users
        WHERE company_id = NEW.company_id
          AND role_id = NEW.role_id
          AND id IS DISTINCT FROM NEW.id
    ) THEN
        RAISE EXCEPTION 'A company can have only one admin';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER users_admin_role_rules
    BEFORE INSERT OR UPDATE OF role_id, company_id ON users
    FOR EACH ROW
    EXECUTE FUNCTION users_admin_role_rules();
