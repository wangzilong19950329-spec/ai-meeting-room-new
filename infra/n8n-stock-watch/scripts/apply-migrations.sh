#!/bin/sh
set -eu

: "${SW_DB_NAME:?SW_DB_NAME is required}"
: "${SW_DB_USER:?SW_DB_USER is required}"
: "${SW_DB_PASSWORD:?SW_DB_PASSWORD is required}"

export PGPASSWORD="$SW_DB_PASSWORD"

psql_sw() {
  psql -v ON_ERROR_STOP=1 -h postgres -U "$SW_DB_USER" -d "$SW_DB_NAME" "$@"
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

sql_literal() {
  printf "'%s'" "$(printf "%s" "$1" | sed "s/'/''/g")"
}

psql_sw <<'SQL' >/dev/null
CREATE TABLE IF NOT EXISTS sw_schema_migrations (
  version text PRIMARY KEY,
  filename text,
  sha256 text,
  checksum text,
  applied_at timestamptz NOT NULL DEFAULT now()
);
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
SQL

for file in /migrations/V*.sql; do
  [ -f "$file" ] || continue
  filename=$(basename "$file")
  version=$(printf "%s" "$filename" | sed 's/__.*$//')
  checksum=$(sha256_file "$file")
  existing=$(psql_sw -tAc "SELECT sha256 FROM sw_schema_migrations WHERE version = $(sql_literal "$version") OR filename = $(sql_literal "$filename") LIMIT 1" | tr -d '[:space:]')

  if [ -n "$existing" ]; then
    if [ "$existing" != "$checksum" ]; then
      echo "[error] migration checksum mismatch: $filename" >&2
      exit 20
    fi
    echo "migration already applied: $filename"
    continue
  fi

  echo "applying migration: $filename"
  {
    printf 'BEGIN;\n'
    cat "$file"
    printf '\nINSERT INTO sw_schema_migrations(version, filename, sha256, checksum) VALUES (%s, %s, %s, %s);\n' \
      "$(sql_literal "$version")" \
      "$(sql_literal "$filename")" \
      "$(sql_literal "$checksum")" \
      "$(sql_literal "$checksum")"
    printf 'COMMIT;\n'
  } | psql_sw >/dev/null
done

echo "migrations are ready"
