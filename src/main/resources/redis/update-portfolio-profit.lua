local portfolioKey = KEYS[1]
local holdingKey = KEYS[2]
local newPrice = tonumber(ARGV[1])

if newPrice == nil then
    return redis.error_reply('newPrice is required')
end

local quantity = tonumber(redis.call('HGET', holdingKey, 'quantity'))
local averagePurchasePrice = tonumber(redis.call('HGET', holdingKey, 'averagePurchasePrice'))

if quantity == nil or averagePurchasePrice == nil then
    return redis.error_reply('portfolio stock holding is missing required fields')
end

if quantity <= 0 or averagePurchasePrice <= 0 then
    return redis.error_reply('quantity and averagePurchasePrice must be positive')
end

local purchaseAmount = tonumber(redis.call('HGET', holdingKey, 'purchaseAmount'))
if purchaseAmount == nil then
    purchaseAmount = averagePurchasePrice * quantity
end

if purchaseAmount <= 0 then
    return redis.error_reply('purchaseAmount must be positive')
end

local totalPurchaseAmount = tonumber(redis.call('HGET', portfolioKey, 'totalPurchaseAmount'))
if totalPurchaseAmount == nil or totalPurchaseAmount <= 0 then
    return redis.error_reply('portfolio totalPurchaseAmount must be positive')
end

local oldStockUnrealizedProfit = tonumber(redis.call('HGET', holdingKey, 'unrealizedProfit') or '0')
local oldPortfolioUnrealizedProfit = tonumber(redis.call('HGET', portfolioKey, 'unrealizedProfit') or '0')
local oldPortfolioReturnRate = tonumber(redis.call('HGET', portfolioKey, 'returnRate') or '0')

local newStockUnrealizedProfit = (newPrice - averagePurchasePrice) * quantity
local newStockReturnRate = newStockUnrealizedProfit / purchaseAmount
local newPortfolioUnrealizedProfit = oldPortfolioUnrealizedProfit - oldStockUnrealizedProfit + newStockUnrealizedProfit
local newPortfolioReturnRate = newPortfolioUnrealizedProfit / totalPurchaseAmount

redis.call(
    'HSET',
    holdingKey,
    'currentPrice', tostring(newPrice),
    'purchaseAmount', tostring(purchaseAmount),
    'unrealizedProfit', tostring(newStockUnrealizedProfit),
    'returnRate', tostring(newStockReturnRate)
)

redis.call(
    'HSET',
    portfolioKey,
    'unrealizedProfit', tostring(newPortfolioUnrealizedProfit),
    'returnRate', tostring(newPortfolioReturnRate)
)

return {
    tostring(oldPortfolioUnrealizedProfit),
    tostring(newPortfolioUnrealizedProfit),
    tostring(oldPortfolioReturnRate),
    tostring(newPortfolioReturnRate)
}
