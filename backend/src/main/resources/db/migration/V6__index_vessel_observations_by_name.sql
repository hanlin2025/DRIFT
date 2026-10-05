CREATE INDEX vessel_observations_vessel_name_latest
    ON vessel_observations (UPPER(BTRIM(REGEXP_REPLACE(vessel_name, '\s+', ' ', 'g'))), ingested_at DESC, id DESC);
