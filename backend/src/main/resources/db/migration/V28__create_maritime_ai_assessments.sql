CREATE TABLE maritime_ai_assessments (
  id BIGSERIAL PRIMARY KEY,
  shipment_id BIGINT NOT NULL REFERENCES shipments(id) ON DELETE CASCADE,
  assessment_id VARCHAR(36) NOT NULL UNIQUE,
  owning_company_id BIGINT NOT NULL REFERENCES companies(id) ON DELETE RESTRICT,
  bundle_version VARCHAR(100) NOT NULL,
  bundle_hash VARCHAR(64) NOT NULL,
  assessment_cutoff_utc TIMESTAMPTZ NOT NULL,
  provider VARCHAR(100) NOT NULL,
  model VARCHAR(200) NOT NULL,
  prompt_version VARCHAR(100) NOT NULL,
  generation_state VARCHAR(16) NOT NULL,
  validation_status VARCHAR(32) NOT NULL,
  output_json TEXT,
  failure_metadata TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT maritime_ai_assessments_state_check CHECK (generation_state IN ('GENERATED', 'ABSTAINED', 'FAILED'))
);
CREATE INDEX maritime_ai_assessments_shipment_created_idx ON maritime_ai_assessments (shipment_id, created_at DESC);
