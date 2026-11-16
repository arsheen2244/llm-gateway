--[[
acquire.lua: atomically check a client's sliding-window limits and, if the request fits,
record it. Called by RedisRateLimiter.tryAcquire. Redis runs the whole script as one
atomic step, so no other gateway instance can act between the check and the update.

STATUS: stub, to be written by the project owner (week 2, step 5).
The behaviour must pass RateLimiterContractTest and RedisScriptTest.

KEYS (all three share the hash tag {clientId}, so they live on one Redis node)
  KEYS[1]  rl:{clientId}:log     sorted set   member = reservation id, score = admit time in ms
  KEYS[2]  rl:{clientId}:tokens  hash         field  = reservation id, value = tokens it holds
  KEYS[3]  rl:{clientId}:sum     string       total tokens of all entries still in the window

ARGV (all arrive as strings: convert with tonumber)
  ARGV[1]  window_ms         window length in milliseconds (60000 in production, 1000 in tests)
  ARGV[2]  max_requests      admitted requests allowed per window
  ARGV[3]  max_tokens        tokens allowed per window
  ARGV[4]  estimated_tokens  tokens to reserve for this request (>= 0)
  ARGV[5]  reservation_id    unique id for this request's entry (a UUID from the gateway)

RETURNS a table of exactly four INTEGERS:
  { allowed, remaining_requests, remaining_tokens, retry_after_ms }

RULES
  1. "now" comes from Redis itself: redis.call('TIME') returns { seconds, microseconds }
     as strings. now_ms = seconds * 1000 + floor(microseconds / 1000).
     (Never trust the gateways' clocks: three instances' clocks drift apart.)
  2. Expire first: every entry with score <= now_ms - window_ms leaves the window.
     Remove it from the log AND the hash, and subtract its tokens from the sum.
  3. If estimated_tokens > max_tokens, the request can NEVER fit:
       return { 0, remaining_requests, remaining_tokens, -1 }   and record nothing.
  4. Admit only if BOTH still hold with this request included:
       count + 1 <= max_requests   and   sum + estimated_tokens <= max_tokens
     Admitted: add the entry (log score now_ms, hash value estimated_tokens, sum += estimate),
       then return { 1, remaining_requests_after, remaining_tokens_after, 0 }.
     Rejected: record NOTHING, and return
       { 0, remaining_requests_now, remaining_tokens_now, retry_after_ms }.
  5. retry_after_ms = the earliest moment this same request would fit. Walk the entries
     oldest first, pretending each one has expired (count - 1, sum - its tokens), until
     both limits in rule 4 hold. The answer is when THAT entry really expires:
       retry_after_ms = its_score + window_ms - now_ms        (always >= 1)
     For tokens this may take several entries, not just the oldest one.
  6. "remaining" values are max - used, after expiry (and after admitting, if allowed).
  7. Keep memory bounded: after admitting, set an expiry on all three keys of window_ms
     (PEXPIRE), so a client that goes quiet leaves nothing behind.
  8. Return integers only. Lua numbers are truncated to integers in the reply; booleans
     and strings would break the Java side, which checks the reply's shape.
--]]

return redis.error_reply('acquire.lua is not implemented yet (week 2, step 5)')
