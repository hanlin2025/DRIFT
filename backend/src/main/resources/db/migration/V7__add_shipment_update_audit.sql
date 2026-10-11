ALTER TABLE shipments
    ADD COLUMN updated_at TIMESTAMPTZ,
    ADD COLUMN updated_by_user_id BIGINT,
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

UPDATE shipments
SET updated_at = created_at,
    updated_by_user_id = created_by_user_id
WHERE updated_at IS NULL
   OR updated_by_user_id IS NULL;

ALTER TABLE shipments
    ALTER COLUMN updated_at SET NOT NULL,
    ALTER COLUMN updated_by_user_id SET NOT NULL,
    ADD CONSTRAINT shipments_updated_by_user_id_fkey
        FOREIGN KEY (updated_by_user_id) REFERENCES users(id);
