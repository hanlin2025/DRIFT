ALTER TABLE shipments
    ADD COLUMN mother_vessel_imo VARCHAR(7),
    ADD COLUMN mother_vessel_mmsi VARCHAR(9),
    ADD COLUMN mother_vessel_operator VARCHAR(200),
    ADD COLUMN feeder_vessel_imo VARCHAR(7),
    ADD COLUMN feeder_vessel_mmsi VARCHAR(9),
    ADD COLUMN feeder_vessel_operator VARCHAR(200),
    ADD CONSTRAINT shipments_mother_vessel_imo_check
        CHECK (mother_vessel_imo IS NULL OR mother_vessel_imo ~ '^[0-9]{7}$'),
    ADD CONSTRAINT shipments_mother_vessel_mmsi_check
        CHECK (mother_vessel_mmsi IS NULL OR mother_vessel_mmsi ~ '^[0-9]{9}$'),
    ADD CONSTRAINT shipments_feeder_vessel_imo_check
        CHECK (feeder_vessel_imo IS NULL OR feeder_vessel_imo ~ '^[0-9]{7}$'),
    ADD CONSTRAINT shipments_feeder_vessel_mmsi_check
        CHECK (feeder_vessel_mmsi IS NULL OR feeder_vessel_mmsi ~ '^[0-9]{9}$');
