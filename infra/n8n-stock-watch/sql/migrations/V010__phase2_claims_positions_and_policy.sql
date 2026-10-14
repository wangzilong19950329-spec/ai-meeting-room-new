CREATE TABLE IF NOT EXISTS sw_report_claim (
  job_id bigint PRIMARY KEY REFERENCES sw_analysis_job(id) ON DELETE CASCADE,
  status text NOT NULL,
  claim_token text NOT NULL,
  claimed_at timestamptz NOT NULL DEFAULT now(),
  completed_at timestamptz,
  last_error text,
  CONSTRAINT sw_report_claim_status_chk CHECK (status IN ('PENDING', 'COMPLETED', 'UNKNOWN'))
);

CREATE TABLE IF NOT EXISTS sw_position (
  symbol text NOT NULL,
  market text NOT NULL,
  total_quantity numeric(30,6) NOT NULL DEFAULT 0,
  available_quantity numeric(30,6) NOT NULL DEFAULT 0,
  source text NOT NULL,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (symbol, market),
  CHECK (total_quantity >= 0),
  CHECK (available_quantity >= 0),
  CHECK (available_quantity <= total_quantity)
);

CREATE TABLE IF NOT EXISTS sw_risk_policy (
  symbol text NOT NULL,
  market text NOT NULL,
  max_stress_loss_yuan numeric(30,6),
  block_new_entry_when_locked boolean NOT NULL DEFAULT true,
  source text NOT NULL,
  metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (symbol, market),
  CHECK (max_stress_loss_yuan IS NULL OR max_stress_loss_yuan >= 0)
);

INSERT INTO sw_position (
  symbol, market, total_quantity, available_quantity, source, metadata
)
VALUES (
  '300750', 'sz', 100, 0, 'LOCAL_QA_FIXTURE',
  '{"limitation":"not a live brokerage position"}'::jsonb
)
ON CONFLICT (symbol, market) DO NOTHING;

INSERT INTO sw_risk_policy (
  symbol, market, max_stress_loss_yuan, block_new_entry_when_locked, source, metadata
)
VALUES (
  '300750', 'sz', 500, true, 'LOCAL_QA_FIXTURE',
  '{"limitation":"local deterministic smoke threshold"}'::jsonb
)
ON CONFLICT (symbol, market) DO NOTHING;
