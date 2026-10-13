ALTER TABLE shipments
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT shipments_status_check
        CHECK (status IN ('ACTIVE', 'ARCHIVED'));
