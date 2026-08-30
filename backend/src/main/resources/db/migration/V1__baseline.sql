CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Sanity: fail the migration loudly if the DB is not UTC.
DO $$
BEGIN
  IF current_setting('TimeZone') <> 'UTC' THEN
    RAISE EXCEPTION 'Database timezone is %, expected UTC', current_setting('TimeZone');
  END IF;
END $$;
