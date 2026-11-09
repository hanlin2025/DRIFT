-- Keep assessment classification outside output_json so damaged or incomplete scenario
-- envelopes can never be mistaken for ordinary shipment history.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1
    FROM maritime_ai_assessments
    WHERE output_json IS NOT NULL
      AND NOT pg_input_is_valid(output_json, 'jsonb')
  ) THEN
    RAISE EXCEPTION 'V34 cannot classify malformed maritime AI assessment JSON; inspect and repair the rows before retrying';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM maritime_ai_assessments
    WHERE output_json IS NOT NULL
      AND jsonb_typeof(output_json::jsonb) IS DISTINCT FROM 'object'
  ) THEN
    RAISE EXCEPTION 'V34 cannot classify non-object maritime AI assessment JSON; inspect the rows before retrying';
  END IF;

  IF EXISTS (
    SELECT 1
    FROM maritime_ai_assessments
    WHERE output_json IS NOT NULL
      AND output_json::jsonb ? 'recordType'
      AND output_json::jsonb->'recordType' <> 'null'::jsonb
      AND (
        jsonb_typeof(output_json::jsonb->'recordType') <> 'string'
        OR output_json::jsonb->>'recordType' NOT IN ('SHIPMENT_ASSESSMENT', 'CONTROLLED_SCENARIO_ASSESSMENT')
      )
      AND NOT (
        output_json::jsonb ? 'scenarioContext'
        AND output_json::jsonb->'scenarioContext' <> 'null'::jsonb
      )
  ) THEN
    RAISE EXCEPTION 'V34 cannot classify an assessment with an unknown recordType and no scenario context; inspect the rows before retrying';
  END IF;
END $$;

ALTER TABLE maritime_ai_assessments
  ADD COLUMN record_type VARCHAR(40) NOT NULL DEFAULT 'SHIPMENT_ASSESSMENT';

-- Prefer privacy when legacy JSON carries scenario evidence, including an envelope with a
-- missing, null, or malformed JSON discriminator. Ordinary rows with absent/null context
-- remain ordinary, including historical envelopes that predate recordType.
-- A scenario envelope whose type and context were both erased before V34 is indistinguishable
-- from an ordinary legacy envelope. No scenario persistence was previously shipped on main;
-- new scenario writes are durable before their JSON is stored.
UPDATE maritime_ai_assessments
SET record_type = 'CONTROLLED_SCENARIO_ASSESSMENT'
WHERE output_json IS NOT NULL
  AND (
    output_json::jsonb->>'recordType' = 'CONTROLLED_SCENARIO_ASSESSMENT'
    OR (
      output_json::jsonb ? 'scenarioContext'
      AND output_json::jsonb->'scenarioContext' <> 'null'::jsonb
    )
  );

ALTER TABLE maritime_ai_assessments
  ADD CONSTRAINT maritime_ai_assessments_record_type_check
  CHECK (record_type IN ('SHIPMENT_ASSESSMENT', 'CONTROLLED_SCENARIO_ASSESSMENT'));
