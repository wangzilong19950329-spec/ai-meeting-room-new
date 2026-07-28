UPDATE sw_schema_migrations
SET version = split_part(filename, '__', 1)
WHERE position('__' in filename) > 0
  AND version = filename;

