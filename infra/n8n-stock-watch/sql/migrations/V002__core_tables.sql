CREATE TABLE IF NOT EXISTS sw_analysis_job (
  id bigserial PRIMARY KEY,
  job_key text NOT NULL UNIQUE,
  trigger_source text NOT NULL,
  symbol text NOT NULL,
  market text NOT NULL,
  fixture_name text NOT NULL,
  model_provider text NOT NULL,
  status text NOT NULL,
  started_at timestamptz NOT NULL,
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sw_market_fact_packet (
  id bigserial PRIMARY KEY,
  job_id bigint NOT NULL REFERENCES sw_analysis_job(id) ON DELETE CASCADE,
  packet_hash text NOT NULL UNIQUE,
  symbol text NOT NULL,
  market text NOT NULL,
  quote_time timestamptz NOT NULL,
  source_kind text NOT NULL,
  quality_status text NOT NULL,
  fact_registry jsonb NOT NULL,
  packet jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sw_model_run (
  id bigserial PRIMARY KEY,
  job_id bigint NOT NULL REFERENCES sw_analysis_job(id) ON DELETE CASCADE,
  packet_id bigint NOT NULL REFERENCES sw_market_fact_packet(id) ON DELETE CASCADE,
  provider text NOT NULL,
  status text NOT NULL,
  raw_response jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sw_behavior_view (
  id bigserial PRIMARY KEY,
  model_run_id bigint NOT NULL REFERENCES sw_model_run(id) ON DELETE CASCADE,
  view jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sw_structural_validation (
  id bigserial PRIMARY KEY,
  model_run_id bigint NOT NULL REFERENCES sw_model_run(id) ON DELETE CASCADE,
  status text NOT NULL,
  errors jsonb NOT NULL DEFAULT '[]'::jsonb,
  validation jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sw_structural_validation_status_chk CHECK (status IN ('accepted', 'rejected'))
);

CREATE TABLE IF NOT EXISTS sw_protection_report (
  id bigserial PRIMARY KEY,
  job_id bigint NOT NULL REFERENCES sw_analysis_job(id) ON DELETE CASCADE,
  packet_id bigint NOT NULL REFERENCES sw_market_fact_packet(id) ON DELETE CASCADE,
  model_run_id bigint NOT NULL REFERENCES sw_model_run(id) ON DELETE CASCADE,
  validation_id bigint NOT NULL REFERENCES sw_structural_validation(id) ON DELETE CASCADE,
  report_type text NOT NULL,
  status text NOT NULL,
  markdown text NOT NULL,
  risk_items jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sw_protection_report_type_chk CHECK (report_type = 'STRESS_ONLY')
);

