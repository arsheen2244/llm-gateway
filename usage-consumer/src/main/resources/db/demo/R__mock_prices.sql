-- Made-up prices for the mock models, for local runs, demos and tests (profile "local").
INSERT INTO model_prices (provider, model, effective_from, input_per_million, output_per_million)
VALUES ('mock-openai', 'mock-gpt', '2020-01-01T00:00:00Z', 1.00, 2.00),
       ('mock-anthropic', 'mock-claude', '2020-01-01T00:00:00Z', 3.00, 15.00)
ON CONFLICT DO NOTHING;
