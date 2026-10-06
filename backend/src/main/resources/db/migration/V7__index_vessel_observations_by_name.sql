CREATE INDEX vessel_observations_vessel_name_latest
    ON vessel_observations (
        btrim(regexp_replace(upper(vessel_name), '[[:space:]]+', ' ', 'g')),
        ingested_at DESC,
        id DESC
    );
