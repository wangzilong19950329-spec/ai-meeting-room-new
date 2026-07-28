INSERT INTO sw_analysis_job (
  job_key,
  trigger_source,
  symbol,
  market,
  fixture_name,
  model_provider,
  status,
  started_at,
  completed_at
)
VALUES (
  'phase1-schema-seed',
  'migration',
  '000000',
  'fixture',
  'none',
  'MOCK',
  'schema_ready',
  '2026-07-28T00:00:00Z',
  '2026-07-28T00:00:00Z'
)
ON CONFLICT (job_key) DO NOTHING;

