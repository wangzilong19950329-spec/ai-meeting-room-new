UPDATE sw_model_run model
SET job_id = packet.job_id
FROM sw_market_fact_packet packet
WHERE model.packet_id = packet.id
  AND model.job_id <> packet.job_id;

UPDATE sw_protection_report report
SET job_id = packet.job_id
FROM sw_market_fact_packet packet
WHERE report.packet_id = packet.id
  AND report.job_id <> packet.job_id;
