local userKey = KEYS[1]
local rankingKey = KEYS[2]
local userId = ARGV[1]
local oldPortfolioUnrealizedProfit = tonumber(ARGV[2])
local newPortfolioUnrealizedProfit = tonumber(ARGV[3])

if userId == nil or oldPortfolioUnrealizedProfit == nil or newPortfolioUnrealizedProfit == nil then
    return redis.error_reply('userId and portfolio unrealized profits are required')
end

local totalPurchaseAmount = tonumber(redis.call('HGET', userKey, 'totalPurchaseAmount'))
if totalPurchaseAmount == nil or totalPurchaseAmount <= 0 then
    return redis.error_reply('user totalPurchaseAmount must be positive')
end

local oldUserUnrealizedProfit = tonumber(redis.call('HGET', userKey, 'unrealizedProfit') or '0')
local oldUserReturnRate = tonumber(redis.call('HGET', userKey, 'returnRate') or '0')
local newUserUnrealizedProfit = oldUserUnrealizedProfit - oldPortfolioUnrealizedProfit + newPortfolioUnrealizedProfit
local newUserReturnRate = newUserUnrealizedProfit / totalPurchaseAmount

redis.call(
    'HSET',
    userKey,
    'unrealizedProfit', tostring(newUserUnrealizedProfit),
    'returnRate', tostring(newUserReturnRate)
)

redis.call('ZADD', rankingKey, tostring(newUserReturnRate), userId)

return {
    tostring(oldUserUnrealizedProfit),
    tostring(newUserUnrealizedProfit),
    tostring(oldUserReturnRate),
    tostring(newUserReturnRate)
}
