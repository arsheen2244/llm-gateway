#!/bin/bash
# Runs once, when PostgreSQL creates its data directory: the role the gateways read usage with.
# What it may read is granted by the consumer's V2__reader_grant.sql.
set -e
psql -v ON_ERROR_STOP=1 -v pw="$USAGE_DB_READER_PASSWORD" -U "$POSTGRES_USER" -d "$POSTGRES_DB" <<'SQL'
CREATE ROLE usage_reader LOGIN PASSWORD :'pw';
SQL
