-- Usage accounting (design note 39).

-- What each provider charges, per million tokens. A price change is a new row with a later
-- effective_from, so events are always priced as of when they happened.
CREATE TABLE model_prices (
    provider           text           NOT NULL,
    model              text           NOT NULL,  -- the name sent to the provider
    effective_from     timestamptz    NOT NULL,
    input_per_million  numeric(12, 6) NOT NULL CHECK (input_per_million >= 0),
    output_per_million numeric(12, 6) NOT NULL CHECK (output_per_million >= 0),
    PRIMARY KEY (provider, model, effective_from)
);

-- One row per request. The primary key is what makes the consumer idempotent.
CREATE TABLE usage_events (
    event_id          uuid            PRIMARY KEY,
    client_id         text            NOT NULL,
    model             text            NOT NULL,
    provider          text,
    upstream_model    text,
    prompt_tokens     integer         NOT NULL CHECK (prompt_tokens >= 0),
    completion_tokens integer         NOT NULL CHECK (completion_tokens >= 0),
    latency_ms        bigint          NOT NULL,
    cache             text            NOT NULL,
    status            text            NOT NULL,
    stream            boolean         NOT NULL,
    occurred_at       timestamptz     NOT NULL,
    cost_usd          numeric(20, 12),           -- NULL: no price was known
    recorded_at       timestamptz     NOT NULL DEFAULT now()
);

CREATE INDEX usage_events_by_client ON usage_events (client_id, occurred_at);

-- Totals per client and UTC day, updated in the same transaction as each new event.
CREATE TABLE usage_daily (
    client_id                text            NOT NULL,
    day                      date            NOT NULL,
    requests                 bigint          NOT NULL,
    successful_requests      bigint          NOT NULL,
    rate_limited_requests    bigint          NOT NULL,
    failed_requests          bigint          NOT NULL,
    cache_hits               bigint          NOT NULL,
    prompt_tokens            bigint          NOT NULL,  -- from providers only
    completion_tokens        bigint          NOT NULL,
    cached_prompt_tokens     bigint          NOT NULL,  -- answered from the cache, not billed
    cached_completion_tokens bigint          NOT NULL,
    cost_usd                 numeric(24, 12) NOT NULL,  -- of the priced requests
    unpriced_requests        bigint          NOT NULL,  -- tokens used, but no price known
    PRIMARY KEY (client_id, day)
);
