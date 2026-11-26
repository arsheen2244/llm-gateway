-- The gateways read daily totals for GET /v1/usage (design note 40). If the usage_reader role
-- exists (created with the database; it has a password, so not here), let it read that one
-- table and nothing else.
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'usage_reader') THEN
        GRANT SELECT ON usage_daily TO usage_reader;
    END IF;
END
$$;
