ALTER TABLE shipments
    ADD COLUMN transshipment_port VARCHAR(200);

UPDATE shipments
SET transshipment_port = 'Singapore'
WHERE transshipment_port IS NULL;

ALTER TABLE shipments
    ALTER COLUMN transshipment_port SET NOT NULL;
