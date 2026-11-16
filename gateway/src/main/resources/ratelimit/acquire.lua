--[[
acquire.lua: atomically check a client's sliding-window limits and, if the request fits,
record it. Called by RedisRateLimiter.tryAcquire. Redis runs the whole script as one
atomic step, so no other gateway instance can act between the check and the update.

Implementation: see the code below this comment, and design note 21.
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

local log_key, tokens_key, sum_key = KEYS[1], KEYS[2], KEYS[3]
local window_ms      = tonumber(ARGV[1])
local max_requests   = tonumber(ARGV[2])
local max_tokens     = tonumber(ARGV[3])
local estimate       = tonumber(ARGV[4])
local reservation_id = ARGV[5]

-- Rule 1: Redis's own clock, in milliseconds.
local time = redis.call('TIME')
local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

-- Rule 2: drop every entry admitted at or before (now - window), from the log AND the
-- hash, and subtract their tokens from the running sum. Done in batches because Lua's
-- unpack() cannot spread thousands of values into one call.
local function expire_old_entries(cutoff)
  local freed = 0
  while true do
    local batch = redis.call('ZRANGEBYSCORE', log_key, '-inf', cutoff, 'LIMIT', 0, 500)
    if #batch == 0 then
      break
    end
    local amounts = redis.call('HMGET', tokens_key, unpack(batch))
    for i = 1, #amounts do
      freed = freed + (tonumber(amounts[i]) or 0)
    end
    redis.call('ZREM', log_key, unpack(batch))
    redis.call('HDEL', tokens_key, unpack(batch))
  end
  local sum = tonumber(redis.call('GET', sum_key)) or 0
  if freed > 0 then
    sum = math.max(0, sum - freed)
    -- KEEPTTL: a plain SET would silently remove the key's expiry (rule 7).
    redis.call('SET', sum_key, sum, 'KEEPTTL')
  end
  return sum
end

local sum = expire_old_entries(now_ms - window_ms)
local count = redis.call('ZCARD', log_key)
local remaining_requests = max_requests - count
local remaining_tokens = max_tokens - sum

-- Rule 3: an estimate above the whole limit can never fit. Record nothing.
if estimate > max_tokens then
  return { 0, remaining_requests, remaining_tokens, -1 }
end

-- Rule 4: admit only if both limits hold with this request included.
if count + 1 <= max_requests and sum + estimate <= max_tokens then
  redis.call('ZADD', log_key, now_ms, reservation_id)
  redis.call('HSET', tokens_key, reservation_id, estimate)
  redis.call('INCRBY', sum_key, estimate)
  -- Rule 7: everything a quiet client stored disappears one window after its last admit.
  redis.call('PEXPIRE', log_key, window_ms)
  redis.call('PEXPIRE', tokens_key, window_ms)
  redis.call('PEXPIRE', sum_key, window_ms)
  return { 1, remaining_requests - 1, remaining_tokens - estimate, 0 }
end

-- Rule 5: rejected. Walk entries oldest first, pretending each has expired, until this
-- request would fit; the answer is when that entry really leaves the window.
-- Paged, so a client with a huge log doesn't load it all at once.
local page_size = 100
local start = 0
local c, s = count, sum
while true do
  local page = redis.call('ZRANGE', log_key, start, start + page_size - 1, 'WITHSCORES')
  if #page == 0 then
    break
  end
  local ids = {}
  for i = 1, #page, 2 do
    ids[#ids + 1] = page[i]
  end
  local amounts = redis.call('HMGET', tokens_key, unpack(ids))
  for j = 1, #ids do
    c = c - 1
    s = s - (tonumber(amounts[j]) or 0)
    if c + 1 <= max_requests and s + estimate <= max_tokens then
      local admitted_at = tonumber(page[2 * j])
      return { 0, remaining_requests, remaining_tokens, admitted_at + window_ms - now_ms }
    end
  end
  start = start + page_size
end

-- Not reachable when estimate <= max_tokens (an empty window always fits), but never
-- return nothing: the Java side requires four integers.
return { 0, remaining_requests, remaining_tokens, window_ms }
