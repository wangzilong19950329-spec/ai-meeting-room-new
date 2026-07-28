CREATE INDEX IF NOT EXISTS idx_sw_analysis_job_created_at ON sw_analysis_job(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_sw_fact_packet_symbol_time ON sw_market_fact_packet(symbol, market, quote_time DESC);
CREATE INDEX IF NOT EXISTS idx_sw_model_run_job_id ON sw_model_run(job_id);
CREATE INDEX IF NOT EXISTS idx_sw_validation_model_run_id ON sw_structural_validation(model_run_id);
CREATE INDEX IF NOT EXISTS idx_sw_protection_report_created_at ON sw_protection_report(created_at DESC);

