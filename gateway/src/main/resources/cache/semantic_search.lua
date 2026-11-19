-- Finds the cached prompt closest in meaning to this one, within one cache scope (design note 35).
--
-- ARGV[1] index name
-- ARGV[2] scope (hex hash: client, model, system prompt, sampling parameters)
-- ARGV[3] the prompt's embedding, as 4-byte little-endian floats
--
-- Returns {answer_json, distance}, or false when the scope has no entries.
-- distance is the cosine distance, 1 - cosine similarity; the caller applies the threshold.
--
-- Run as a script rather than a plain command so the reply always has the classic (RESP2)
-- shape, whatever protocol the client connection negotiated.

local reply = redis.call('FT.SEARCH', ARGV[1],
    '(@scope:{' .. ARGV[2] .. '})=>[KNN 1 @vector $vec AS distance]',
    'PARAMS', '2', 'vec', ARGV[3],
    'SORTBY', 'distance',
    'RETURN', '2', 'distance', 'answer',
    'LIMIT', '0', '1',
    'DIALECT', '2')

-- reply = {total, key, {field, value, field, value}}
if reply[1] == 0 or reply[3] == nil then
    return false
end

local fields = {}
local list = reply[3]
for i = 1, #list, 2 do
    fields[list[i]] = list[i + 1]
end
if fields['answer'] == nil or fields['distance'] == nil then
    return false
end
return {fields['answer'], fields['distance']}
