CREATE TABLE shipment_importer_constraints (
    shipment_id BIGINT NOT NULL REFERENCES shipments(id),
    importer_company_id BIGINT NOT NULL REFERENCES companies(id),
    transshipment_port VARCHAR(200) NOT NULL,
    transshipment_arrival_deadline TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (shipment_id, importer_company_id)
);
