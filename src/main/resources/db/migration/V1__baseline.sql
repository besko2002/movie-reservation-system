-- V1 baseline migration.
-- Phase 1 intentionally creates no domain tables: every later phase ships its own
-- migration (V2 users, V3 movies, V4 theaters, ...). This migration only pins the
-- start of the Flyway history so the schema has a known baseline.
DO $$
BEGIN
    -- no-op
END
$$;
