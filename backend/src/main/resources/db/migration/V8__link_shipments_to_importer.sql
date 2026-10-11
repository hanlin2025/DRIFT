ALTER TABLE shipments
    ADD COLUMN importer_company_id BIGINT,
    ADD CONSTRAINT shipments_importer_company_id_fkey
        FOREIGN KEY (importer_company_id) REFERENCES companies(id);

ALTER TABLE shipments
    ADD CONSTRAINT shipments_importer_is_another_company
        CHECK (importer_company_id IS NULL OR importer_company_id <> company_id);

CREATE INDEX shipments_importer_company_idx ON shipments (importer_company_id);
