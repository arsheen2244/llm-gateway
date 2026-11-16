--[[
reconcile.lua: replace an admitted request's reserved tokens with its actual usage.
Called by RedisRateLimiter.reconcile after the provider has answered.

Implementation: see the code below this comment, and design note 21.
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

local log_key, tokens_key, sum_key = KEYS[1], KEYS[2], KEYS[3]
local window_ms      = tonumber(ARGV[1])
local reservation_id = ARGV[2]
local actual         = math.max(0, tonumber(ARGV[3]))

local time = redis.call('TIME')
local now_ms = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

-- Rule 1: the same expiry as acquire.lua. Lua scripts cannot import each other, so this
-- function is duplicated on purpose (Redis 7 "Functions" could share it, at the cost of
-- a separate deployment step).
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
    redis.call('SET', sum_key, sum, 'KEEPTTL')
  end
  return sum
end

local sum = expire_old_entries(now_ms - window_ms)

-- Rule 2: an entry that has left the window (or never existed) no longer counts.
-- HGET returns false (Lua's version of nil from Redis) for a missing field.
local reserved = redis.call('HGET', tokens_key, reservation_id)
if not reserved then
  return { 0, sum }
end

-- Rule 3: swap the reserved amount for the actual one. INCRBY keeps the key's TTL.
redis.call('HSET', tokens_key, reservation_id, actual)
local new_sum = redis.call('INCRBY', sum_key, actual - tonumber(reserved))

-- Rule 4: never negative (only possible if the keys were changed by hand).
if new_sum < 0 then
  redis.call('SET', sum_key, 0, 'KEEPTTL')
  new_sum = 0
end
return { 1, new_sum }
