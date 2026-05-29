package depth.finvibe.profit.worker.infrastructure.redis;

import depth.finvibe.profit.worker.application.ProfitWorkerMetrics;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisPortfolioStateStore implements PortfolioStateStore {

    private final StringRedisTemplate redisTemplate;
    private final ProfitWorkerMetrics metrics;

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
        return getHashLong(portfolioHashKey(portfolioId), "pv");
    }

    @Override
    public Long findCurrentValue(Long portfolioId) {
        return getHashLong(portfolioHashKey(portfolioId), "cv");
    }

    @Override
    public Long calculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;

        try {
            Long quantity = getLong(portfolioStockQuantityKey(portfolioId, changedStockId));
            if (quantity == 0L) {
                result = ProfitWorkerMetrics.RESULT_SUCCESS;
                return getHashLong(portfolioHashKey(portfolioId), "cv");
            }

            String stockCurrentValueKey = portfolioStockCurrentValueKey(portfolioId, changedStockId);
            Long oldStockCurrentValue = getLong(stockCurrentValueKey);
            Long newStockCurrentValue = newPrice * quantity;
            Long delta = newStockCurrentValue - oldStockCurrentValue;

            if (delta != 0L) {
                incrementHash(portfolioHashKey(portfolioId), "cv", delta);
            }
            redisTemplate.opsForValue().set(stockCurrentValueKey, String.valueOf(newStockCurrentValue));

            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return getHashLong(portfolioHashKey(portfolioId), "cv");
        } finally {
            metrics.recordRedisDuration(ProfitWorkerMetrics.OPERATION_PORTFOLIO_CURRENT_VALUE, result, sample);
        }
    }

    @Override
    public Long findAssetCount(Long portfolioId) {
        return getHashLong(portfolioHashKey(portfolioId), "ac");
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
        incrementHash(portfolioHashKey(portfolioId), "pv", amount);
    }

    @Override
    public void subtractPurchasedValue(Long portfolioId, Long amount) {
        incrementHash(portfolioHashKey(portfolioId), "pv", -amount);
    }

    @Override
    public void addCurrentValue(Long portfolioId, Long amount) {
        incrementHash(portfolioHashKey(portfolioId), "cv", amount);
    }

    @Override
    public void subtractCurrentValue(Long portfolioId, Long amount) {
        incrementHash(portfolioHashKey(portfolioId), "cv", -amount);
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
        incrementHash(portfolioHashKey(portfolioId), "ac", 1L);
    }

    @Override
    public void decreaseAssetCount(Long portfolioId) {
        incrementHash(portfolioHashKey(portfolioId), "ac", -1L);
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
        redisTemplate.delete(portfolioHashKey(portfolioId));
    }

    private Long getHashLong(String key, String field) {
        Object value = redisTemplate.opsForHash().get(key, field);
        if (value == null) {
            return 0L;
        }
        return parseLong(value.toString());
    }

    private Long getLong(String key) {
        String value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return 0L;
        }
        return parseLong(value);
    }

    private Long parseLong(String value) {
        if (value.contains(".")) {
            return Math.round(Double.parseDouble(value));
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

    private Long incrementHash(String key, String field, Long delta) {
        Long value = redisTemplate.opsForHash().increment(key, field, delta);
        if (value == null) {
            return 0L;
        }
        return value;
    }

    private String stockPortfoliosKey(Long stockId) {
        return "stock:" + stockId + ":portfolios";
    }

    private String portfolioHashKey(Long portfolioId) {
        return "pf:" + portfolioId;
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
