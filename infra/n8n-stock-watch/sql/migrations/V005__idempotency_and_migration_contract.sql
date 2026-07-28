ALTER TABLE sw_schema_migrations ADD COLUMN IF NOT EXISTS filename text;
ALTER TABLE sw_schema_migrations ADD COLUMN IF NOT EXISTS sha256 text;
ALTER TABLE sw_schema_migrations ADD COLUMN IF NOT EXISTS checksum text;

UPDATE sw_schema_migrations SET filename = version WHERE filename IS NULL;
UPDATE sw_schema_migrations SET sha256 = checksum WHERE sha256 IS NULL AND checksum IS NOT NULL;
UPDATE sw_schema_migrations SET checksum = sha256 WHERE checksum IS NULL AND sha256 IS NOT NULL;

ALTER TABLE sw_schema_migrations ALTER COLUMN filename SET NOT NULL;
ALTER TABLE sw_schema_migrations ALTER COLUMN sha256 SET NOT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'sw_schema_migrations_filename_key'
      AND conrelid = 'sw_schema_migrations'::regclass
  ) THEN
    ALTER TABLE sw_schema_migrations ADD CONSTRAINT sw_schema_migrations_filename_key UNIQUE (filename);
  END IF;
END $$;

WITH keep AS (
  SELECT MAX(id) AS id
  FROM sw_model_run
  GROUP BY packet_id, provider
),
dupe AS (
  SELECT model.id
  FROM sw_model_run model
  LEFT JOIN keep ON keep.id = model.id
  WHERE keep.id IS NULL
)
DELETE FROM sw_model_run
WHERE id IN (SELECT id FROM dupe);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'sw_model_run_packet_provider_key'
      AND conrelid = 'sw_model_run'::regclass
  ) THEN
    ALTER TABLE sw_model_run ADD CONSTRAINT sw_model_run_packet_provider_key UNIQUE (packet_id, provider);
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'sw_behavior_view_model_run_key'
      AND conrelid = 'sw_behavior_view'::regclass
  ) THEN
    ALTER TABLE sw_behavior_view ADD CONSTRAINT sw_behavior_view_model_run_key UNIQUE (model_run_id);
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'sw_structural_validation_model_run_key'
      AND conrelid = 'sw_structural_validation'::regclass
  ) THEN
    ALTER TABLE sw_structural_validation ADD CONSTRAINT sw_structural_validation_model_run_key UNIQUE (model_run_id);
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'sw_protection_report_packet_key'
      AND conrelid = 'sw_protection_report'::regclass
  ) THEN
    ALTER TABLE sw_protection_report ADD CONSTRAINT sw_protection_report_packet_key UNIQUE (packet_id);
  END IF;
END $$;

