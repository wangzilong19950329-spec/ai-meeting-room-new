UPDATE sw_schema_migrations
SET version = regexp_replace(filename, '__.*$', '')
WHERE filename ~ '^V[0-9]+__.*\\.sql$'
  AND version = filename;

