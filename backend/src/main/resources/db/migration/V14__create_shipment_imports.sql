CREATE TABLE shipment_imports (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    imported_count INTEGER NOT NULL,
    failed_count INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX shipment_imports_company_idx ON shipment_imports (company_id, created_at DESC);

CREATE TABLE shipment_import_errors (
    id BIGSERIAL PRIMARY KEY,
    import_id BIGINT NOT NULL REFERENCES shipment_imports(id),
    row_number INTEGER NOT NULL,
    reason VARCHAR(500) NOT NULL
);

CREATE INDEX shipment_import_errors_import_idx ON shipment_import_errors (import_id, row_number);
