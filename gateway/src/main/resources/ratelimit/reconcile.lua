--[[
reconcile.lua: replace an admitted request's reserved tokens with its actual usage.
Called by RedisRateLimiter.reconcile after the provider has answered.

STATUS: stub, to be written by the project owner (week 2, step 5).
The behaviour must pass RateLimiterContractTest and RedisScriptTest.

KEYS: the same three keys as acquire.lua
  KEYS[1]  rl:{clientId}:log     KEYS[2]  rl:{clientId}:tokens     KEYS[3]  rl:{clientId}:sum

ARGV
  ARGV[1]  window_ms       the window the entry was admitted into
  ARGV[2]  reservation_id  the entry to adjust
  ARGV[3]  actual_tokens   actual usage (the gateway already clamps it to >= 0)

RETURNS a table of exactly two INTEGERS:
  { applied, token_sum }

RULES
  1. Expire first, exactly as in acquire.lua (same "now" from TIME, same <= rule),
     so an entry that has left the window is not touched.
  2. If the entry is no longer in the window (or never was): change nothing and
     return { 0, current_sum }. It no longer counts, so there is nothing to correct.
  3. Otherwise: sum = sum - old_tokens + actual_tokens, store actual_tokens for the
     entry, and return { 1, new_sum }.
  4. The sum must never go negative.
--]]

return redis.error_reply('reconcile.lua is not implemented yet (week 2, step 5)')
