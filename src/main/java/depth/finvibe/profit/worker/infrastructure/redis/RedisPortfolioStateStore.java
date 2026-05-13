package depth.finvibe.profit.worker.infrastructure.redis;

import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisPortfolioStateStore implements PortfolioStateStore {

    private final StringRedisTemplate redisTemplate;

    @Override
    public List<Long> findPortfolioIdsByStockId(Long stockId) {
        Set<String> portfolioIds = redisTemplate.opsForSet().members(stockPortfoliosKey(stockId));
        if (portfolioIds == null) {
            return List.of();
        }

        return portfolioIds.stream()
                .map(Long::valueOf)
                .toList();
    }

    @Override
    public Long findPurchasedValue(Long portfolioId) {
        return getLong(portfolioPurchasedValueKey(portfolioId));
    }

    @Override
    public Long findCurrentValue(Long portfolioId) {
        return getLong(portfolioCurrentValueKey(portfolioId));
    }

    @Override
    public Long calculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
        Long quantity = getLong(portfolioStockQuantityKey(portfolioId, changedStockId));
        if (quantity == 0L) {
            return getLong(portfolioCurrentValueKey(portfolioId));
        }

        String stockCurrentValueKey = portfolioStockCurrentValueKey(portfolioId, changedStockId);
        Long oldStockCurrentValue = getLong(stockCurrentValueKey);
        Long newStockCurrentValue = newPrice * quantity;
        Long delta = newStockCurrentValue - oldStockCurrentValue;

        if (delta != 0L) {
            increment(portfolioCurrentValueKey(portfolioId), delta);
        }
        redisTemplate.opsForValue().set(stockCurrentValueKey, String.valueOf(newStockCurrentValue));

        return getLong(portfolioCurrentValueKey(portfolioId));
    }

    @Override
    public Long findAssetCount(Long portfolioId) {
        return getLong(portfolioAssetCountKey(portfolioId));
    }

    @Override
    public boolean increaseStockQuantity(Long stockId, Long portfolioId, Long quantity) {
        String quantityKey = portfolioStockQuantityKey(portfolioId, stockId);
        Long previousQuantity = getLong(quantityKey);

        increment(quantityKey, quantity);
        redisTemplate.opsForSet().add(stockPortfoliosKey(stockId), String.valueOf(portfolioId));
        redisTemplate.opsForSet().add(portfolioStocksKey(portfolioId), String.valueOf(stockId));

        return previousQuantity == 0L;
    }

    @Override
    public boolean decreaseStockQuantity(Long stockId, Long portfolioId, Long quantity) {
        String quantityKey = portfolioStockQuantityKey(portfolioId, stockId);
        Long nextQuantity = increment(quantityKey, -quantity);

        if (nextQuantity > 0L) {
            return false;
        }

        redisTemplate.delete(quantityKey);
        redisTemplate.opsForSet().remove(stockPortfoliosKey(stockId), String.valueOf(portfolioId));
        redisTemplate.opsForSet().remove(portfolioStocksKey(portfolioId), String.valueOf(stockId));
        return true;
    }

    @Override
    public void addPurchasedValue(Long portfolioId, Long amount) {
        increment(portfolioPurchasedValueKey(portfolioId), amount);
    }

    @Override
    public void subtractPurchasedValue(Long portfolioId, Long amount) {
        increment(portfolioPurchasedValueKey(portfolioId), -amount);
    }

    @Override
    public void addCurrentValue(Long portfolioId, Long amount) {
        increment(portfolioCurrentValueKey(portfolioId), amount);
    }

    @Override
    public void subtractCurrentValue(Long portfolioId, Long amount) {
        increment(portfolioCurrentValueKey(portfolioId), -amount);
    }

    @Override
    public void addStockCurrentValue(Long stockId, Long portfolioId, Long amount) {
        increment(portfolioStockCurrentValueKey(portfolioId, stockId), amount);
    }

    @Override
    public void subtractStockCurrentValue(Long stockId, Long portfolioId, Long amount) {
        String key = portfolioStockCurrentValueKey(portfolioId, stockId);
        Long nextValue = increment(key, -amount);
        if (nextValue <= 0L) {
            redisTemplate.delete(key);
        }
    }

    @Override
    public void increaseAssetCount(Long portfolioId) {
        increment(portfolioAssetCountKey(portfolioId), 1L);
    }

    @Override
    public void decreaseAssetCount(Long portfolioId) {
        increment(portfolioAssetCountKey(portfolioId), -1L);
    }

    @Override
    public void deletePortfolioState(Long portfolioId) {
        Set<String> stockIds = redisTemplate.opsForSet().members(portfolioStocksKey(portfolioId));
        if (stockIds != null) {
            for (String stockId : stockIds) {
                redisTemplate.delete(portfolioStockQuantityKey(portfolioId, Long.valueOf(stockId)));
                redisTemplate.delete(portfolioStockCurrentValueKey(portfolioId, Long.valueOf(stockId)));
                redisTemplate.opsForSet().remove(stockPortfoliosKey(Long.valueOf(stockId)), String.valueOf(portfolioId));
            }
        }

        redisTemplate.delete(portfolioStocksKey(portfolioId));
        redisTemplate.delete(portfolioPurchasedValueKey(portfolioId));
        redisTemplate.delete(portfolioCurrentValueKey(portfolioId));
        redisTemplate.delete(portfolioAssetCountKey(portfolioId));
        redisTemplate.delete(portfolioProfitRateKey(portfolioId));
        redisTemplate.delete(portfolioUpdatedAtKey(portfolioId));
    }

    private Long getLong(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0L;
        }
        return Long.valueOf(value);
    }

    private Long increment(String key, Long delta) {
        Long value = redisTemplate.opsForValue().increment(key, delta);
        if (value == null) {
            return 0L;
        }
        return value;
    }

    private String stockPortfoliosKey(Long stockId) {
        return "stock:" + stockId + ":portfolios";
    }

    private String portfolioPurchasedValueKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":purchased-value";
    }

    private String portfolioCurrentValueKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":current-value";
    }

    private String portfolioAssetCountKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":asset-count";
    }

    private String portfolioProfitRateKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":profit-rate";
    }

    private String portfolioUpdatedAtKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":updated-at";
    }

    private String portfolioStocksKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":stocks";
    }

    private String portfolioStockQuantityKey(Long portfolioId, Long stockId) {
        return "portfolio:" + portfolioId + ":stock:" + stockId + ":quantity";
    }

    private String portfolioStockCurrentValueKey(Long portfolioId, Long stockId) {
        return "portfolio:" + portfolioId + ":stock:" + stockId + ":current-value";
    }
}
