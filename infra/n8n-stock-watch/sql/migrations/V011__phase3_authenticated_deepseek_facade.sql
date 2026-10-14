UPDATE sw_runtime_control
SET deepseek_base_url = 'http://host.docker.internal:8793/v1',
    updated_at = now()
WHERE id = 1
  AND deepseek_base_url = 'http://host.docker.internal:8789/v1';
