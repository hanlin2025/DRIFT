CREATE TABLE shipments (
    id BIGSERIAL PRIMARY KEY,
    company_id BIGINT NOT NULL REFERENCES companies(id),
    created_by_user_id BIGINT NOT NULL REFERENCES users(id),
    shipment_reference VARCHAR(100) NOT NULL,
    origin VARCHAR(200) NOT NULL,
    destination VARCHAR(200) NOT NULL,
    mother_vessel VARCHAR(200) NOT NULL,
    planned_mother_arrival_at TIMESTAMPTZ NOT NULL,
    feeder_vessel VARCHAR(200) NOT NULL,
    planned_feeder_departure_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT shipments_itinerary_timing_check
        CHECK (planned_feeder_departure_at > planned_mother_arrival_at)
);

CREATE UNIQUE INDEX shipments_company_reference_key
    ON shipments (company_id, LOWER(shipment_reference));
