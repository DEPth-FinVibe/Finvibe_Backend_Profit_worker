package depth.finvibe.profit.worker.infrastructure.redis;

import depth.finvibe.profit.worker.application.ProfitWorkerMetrics;
import depth.finvibe.profit.worker.application.ValuationDecimalSupport;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisPortfolioStateStore implements PortfolioStateStore {

    private static final String PRECISE_CURRENT_VALUE_FIELD = "cvp";

    private final StringRedisTemplate redisTemplate;
    private final ProfitWorkerMetrics metrics;

    @Override
    public List<Long> findPortfolioIdsByStockId(Long stockId) {
        Set<String> portfolioIds = members(stockPortfoliosKey(stockId));
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
    public BigDecimal findCurrentValue(Long portfolioId) {
        return getPortfolioCurrentValue(portfolioId);
    }

    @Override
    public BigDecimal calculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;

        try {
            BigDecimal quantity = getDecimal(portfolioStockQuantityKey(portfolioId, changedStockId));
            if (quantity.signum() == 0) {
                result = ProfitWorkerMetrics.RESULT_SUCCESS;
                return getPortfolioCurrentValue(portfolioId);
            }

            String stockCurrentValueKey = portfolioStockCurrentValueKey(portfolioId, changedStockId);
            BigDecimal oldStockCurrentValue = getDecimal(stockCurrentValueKey);
            BigDecimal newStockCurrentValue = BigDecimal.valueOf(newPrice).multiply(quantity);
            BigDecimal delta = newStockCurrentValue.subtract(oldStockCurrentValue);
            BigDecimal nextPortfolioCurrentValue = getPortfolioCurrentValue(portfolioId).add(delta);

            setHashDecimal(portfolioHashKey(portfolioId), PRECISE_CURRENT_VALUE_FIELD, nextPortfolioCurrentValue);
            setDecimal(stockCurrentValueKey, newStockCurrentValue);

            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return nextPortfolioCurrentValue;
        } finally {
            metrics.recordRedisDuration(ProfitWorkerMetrics.OPERATION_PORTFOLIO_CURRENT_VALUE, result, sample);
        }
    }

    @Override
    public Long findAssetCount(Long portfolioId) {
        return getHashLong(portfolioHashKey(portfolioId), "ac");
    }

    @Override
    public boolean increaseStockQuantity(Long stockId, Long portfolioId, BigDecimal quantity) {
        String quantityKey = portfolioStockQuantityKey(portfolioId, stockId);
        BigDecimal previousQuantity = getDecimal(quantityKey);

        setDecimal(quantityKey, previousQuantity.add(quantity));
        redisTemplate.opsForSet().add(stockPortfoliosKey(stockId), String.valueOf(portfolioId));
        redisTemplate.opsForSet().add(portfolioStocksKey(portfolioId), String.valueOf(stockId));

        return previousQuantity.signum() == 0;
    }

    @Override
    public boolean decreaseStockQuantity(Long stockId, Long portfolioId, BigDecimal quantity) {
        String quantityKey = portfolioStockQuantityKey(portfolioId, stockId);
        BigDecimal nextQuantity = getDecimal(quantityKey).subtract(quantity);

        if (nextQuantity.signum() > 0) {
            setDecimal(quantityKey, nextQuantity);
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
    public void addCurrentValue(Long portfolioId, BigDecimal amount) {
        setHashDecimal(portfolioHashKey(portfolioId), PRECISE_CURRENT_VALUE_FIELD, getPortfolioCurrentValue(portfolioId).add(amount));
    }

    @Override
    public void subtractCurrentValue(Long portfolioId, BigDecimal amount) {
        setHashDecimal(portfolioHashKey(portfolioId), PRECISE_CURRENT_VALUE_FIELD, getPortfolioCurrentValue(portfolioId).subtract(amount));
    }

    @Override
    public void addStockCurrentValue(Long stockId, Long portfolioId, BigDecimal amount) {
        String key = portfolioStockCurrentValueKey(portfolioId, stockId);
        setDecimal(key, getDecimal(key).add(amount));
    }

    @Override
    public void subtractStockCurrentValue(Long stockId, Long portfolioId, BigDecimal amount) {
        String key = portfolioStockCurrentValueKey(portfolioId, stockId);
        BigDecimal nextValue = getDecimal(key).subtract(amount);
        if (nextValue.signum() <= 0) {
            redisTemplate.delete(key);
            return;
        }
        setDecimal(key, nextValue);
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
        Object value = hashGet(key, field);
        if (value == null) {
            return 0L;
        }
        return Long.valueOf(value.toString());
    }

    private BigDecimal getHashDecimal(String key, String field) {
        Object value = hashGet(key, field);
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value.toString());
    }

    private BigDecimal getDecimal(String key) {
        String value = valueGet(key);
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(value);
    }

    private Long incrementHash(String key, String field, Long delta) {
        Long value = hashIncrement(key, field, delta);
        if (value == null) {
            return 0L;
        }
        return value;
    }

    private BigDecimal getPortfolioCurrentValue(Long portfolioId) {
        String key = portfolioHashKey(portfolioId);
        Object preciseValue = hashGet(key, PRECISE_CURRENT_VALUE_FIELD);
        if (preciseValue != null) {
            return new BigDecimal(preciseValue.toString());
        }
        return BigDecimal.valueOf(getHashLong(key, "cv"));
    }

    private void setDecimal(String key, BigDecimal value) {
        valueSet(key, toPlainString(value));
    }

    private void setHashDecimal(String key, String field, BigDecimal value) {
        hashPut(key, field, toPlainString(value));
    }

    private Object hashGet(String key, String field) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            Object value = redisTemplate.opsForHash().get(key, field);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return value;
        } finally {
            metrics.recordRedisCommandDuration("hash_get", result, sample);
        }
    }

    private void hashPut(String key, String field, String value) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            redisTemplate.opsForHash().put(key, field, value);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordRedisCommandDuration("hash_put", result, sample);
        }
    }

    private Long hashIncrement(String key, String field, Long delta) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            Long value = redisTemplate.opsForHash().increment(key, field, delta);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return value;
        } finally {
            metrics.recordRedisCommandDuration("hash_increment", result, sample);
        }
    }

    private String valueGet(String key) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            String value = redisTemplate.opsForValue().get(key);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return value;
        } finally {
            metrics.recordRedisCommandDuration("value_get", result, sample);
        }
    }

    private void valueSet(String key, String value) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            redisTemplate.opsForValue().set(key, value);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordRedisCommandDuration("value_set", result, sample);
        }
    }

    private Set<String> members(String key) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            Set<String> values = redisTemplate.opsForSet().members(key);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return values;
        } finally {
            metrics.recordRedisCommandDuration("set_members", result, sample);
        }
    }

    private String toPlainString(BigDecimal value) {
        return ValuationDecimalSupport.normalized(value).toPlainString();
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
