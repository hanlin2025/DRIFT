CREATE TABLE vessel_observations (
    id BIGSERIAL PRIMARY KEY,
    mmsi VARCHAR(9) NOT NULL CHECK (mmsi ~ '^[1-9][0-9]{8}$'),
    vessel_name VARCHAR(200),
    latitude NUMERIC NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude NUMERIC NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    speed_over_ground_knots NUMERIC,
    course_over_ground_degrees NUMERIC,
    true_heading_degrees INTEGER,
    navigational_status INTEGER,
    position_valid BOOLEAN,
    ais_utc_second INTEGER CHECK (ais_utc_second BETWEEN 0 AND 59),
    ingested_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(16) NOT NULL CHECK (source IN ('AIS_STREAM', 'SEEDED'))
);

CREATE INDEX vessel_observations_mmsi_latest
    ON vessel_observations (mmsi, ingested_at DESC, id DESC);
