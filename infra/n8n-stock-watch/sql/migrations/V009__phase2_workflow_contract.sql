CREATE TABLE IF NOT EXISTS sw_runtime_control (
  id smallint PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  ingest_enabled boolean NOT NULL DEFAULT false,
  analysis_enabled boolean NOT NULL DEFAULT false,
  publisher_enabled boolean NOT NULL DEFAULT false,
  notify_enabled boolean NOT NULL DEFAULT false,
  model_provider text NOT NULL DEFAULT 'MOCK',
  java_base_url text NOT NULL DEFAULT 'http://host.docker.internal:8127',
  deepseek_base_url text NOT NULL DEFAULT 'http://host.docker.internal:8789/v1',
  notification_transport text NOT NULL DEFAULT 'MAILPIT',
  notification_recipient text NOT NULL DEFAULT 'stock-watch-test@local.invalid',
  manual_test_mode boolean NOT NULL DEFAULT false,
  manual_test_trade_date date,
  manual_test_run_slot text,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT sw_runtime_control_model_provider_chk
    CHECK (model_provider IN ('MOCK', 'DEEPSEEK_WEB')),
  CONSTRAINT sw_runtime_control_notification_transport_chk
    CHECK (notification_transport = 'MAILPIT')
);

INSERT INTO sw_runtime_control (id)
VALUES (1)
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS sw_watchlist (
  id bigserial PRIMARY KEY,
  symbol text NOT NULL,
  market text NOT NULL,
  enabled boolean NOT NULL DEFAULT false,
  source text NOT NULL DEFAULT 'LOCAL_CONFIG',
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (symbol, market)
);

INSERT INTO sw_watchlist (symbol, market, enabled, source, metadata)
VALUES ('300750', 'sz', true, 'PHASE2_SEED', '{"label":"CATL local validation"}'::jsonb)
ON CONFLICT (symbol, market) DO NOTHING;

CREATE TABLE IF NOT EXISTS sw_quote_snapshot (
  id bigserial PRIMARY KEY,
  symbol text NOT NULL,
  market text NOT NULL,
  quote_time timestamptz NOT NULL,
  fetched_at timestamptz NOT NULL,
  provider text NOT NULL,
  source text NOT NULL,
  quality_status text NOT NULL,
  price numeric(20,6) NOT NULL,
  open numeric(20,6),
  day_high numeric(20,6),
  day_low numeric(20,6),
  previous_close numeric(20,6),
  cumulative_volume_raw numeric(30,6),
  cumulative_volume_shares numeric(30,6),
  volume_unit text,
  cumulative_amount_raw numeric(30,6),
  amount_unit text,
  limitations jsonb NOT NULL DEFAULT '[]'::jsonb,
  raw_payload jsonb NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (symbol, market, provider, quote_time)
);

CREATE INDEX IF NOT EXISTS idx_sw_quote_snapshot_symbol_time
  ON sw_quote_snapshot(symbol, market, quote_time DESC);

CREATE TABLE IF NOT EXISTS sw_bar_1m (
  id bigserial PRIMARY KEY,
  symbol text NOT NULL,
  market text NOT NULL,
  minute_start timestamptz NOT NULL,
  open numeric(20,6) NOT NULL,
  high numeric(20,6) NOT NULL,
  low numeric(20,6) NOT NULL,
  close numeric(20,6) NOT NULL,
  volume_delta_raw numeric(30,6),
  volume_delta_shares numeric(30,6),
  amount_delta numeric(30,6),
  volume_unit text,
  amount_unit text,
  vwap numeric(20,6),
  source text NOT NULL,
  quality_status text NOT NULL,
  limitations jsonb NOT NULL DEFAULT '[]'::jsonb,
  first_snapshot_id bigint REFERENCES sw_quote_snapshot(id),
  last_snapshot_id bigint NOT NULL REFERENCES sw_quote_snapshot(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (symbol, market, minute_start)
);

ALTER TABLE sw_analysis_job ADD COLUMN IF NOT EXISTS trade_date date;
ALTER TABLE sw_analysis_job ADD COLUMN IF NOT EXISTS run_slot text;
ALTER TABLE sw_analysis_job ADD COLUMN IF NOT EXISTS analysis_mode text NOT NULL DEFAULT 'MARKET_BEHAVIOR';
ALTER TABLE sw_analysis_job ADD COLUMN IF NOT EXISTS attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE sw_analysis_job ADD COLUMN IF NOT EXISTS max_attempts integer NOT NULL DEFAULT 3;
ALTER TABLE sw_analysis_job ADD COLUMN IF NOT EXISTS next_retry_at timestamptz;
ALTER TABLE sw_analysis_job ADD COLUMN IF NOT EXISTS locked_by text;
ALTER TABLE sw_analysis_job ADD COLUMN IF NOT EXISTS locked_at timestamptz;
ALTER TABLE sw_analysis_job ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE sw_analysis_job ADD COLUMN IF NOT EXISTS last_error_code text;

CREATE UNIQUE INDEX IF NOT EXISTS uq_sw_analysis_job_slot
  ON sw_analysis_job(symbol, market, trade_date, run_slot, analysis_mode)
  WHERE trade_date IS NOT NULL AND run_slot IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sw_analysis_job_dispatch
  ON sw_analysis_job(status, next_retry_at, created_at);

CREATE TABLE IF NOT EXISTS sw_run_failure (
  id bigserial PRIMARY KEY,
  workflow_name text NOT NULL,
  execution_id text,
  error_code text NOT NULL,
  error_message text NOT NULL,
  retryable boolean NOT NULL DEFAULT false,
  context jsonb NOT NULL DEFAULT '{}'::jsonb,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sw_notification_delivery (
  id bigserial PRIMARY KEY,
  report_id bigint NOT NULL REFERENCES sw_protection_report(id) ON DELETE CASCADE,
  transport text NOT NULL,
  recipient_masked text NOT NULL,
  status text NOT NULL,
  provider_message_id text,
  error_message text,
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (report_id, transport, recipient_masked)
);
