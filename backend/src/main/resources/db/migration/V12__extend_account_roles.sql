ALTER TABLE users
    DROP CONSTRAINT users_role_check;

ALTER TABLE users
    ADD CONSTRAINT users_role_check
        CHECK (role IN ('ADMIN', 'LOGISTICS_MANAGER', 'FREIGHT_FORWARDER', 'IMPORTER'));

ALTER TABLE users
    ADD CONSTRAINT users_admin_has_company_check
        CHECK (role <> 'ADMIN' OR company_id IS NOT NULL);

CREATE UNIQUE INDEX users_one_admin_per_company
    ON users (company_id)
    WHERE role = 'ADMIN';
