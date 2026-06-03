package depth.finvibe.profit.worker.infrastructure.redis;

import depth.finvibe.profit.worker.application.ProfitWorkerMetrics;
import depth.finvibe.profit.worker.application.ValuationDecimalSupport;
import depth.finvibe.profit.worker.application.port.out.PortfolioStateStore;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        return recalculateCurrentValue(portfolioId, changedStockId, newPrice).currentValue();
    }

    @Override
    public PortfolioCurrentValueUpdate recalculateCurrentValue(Long portfolioId, Long changedStockId, Long newPrice) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;

        try {
            BigDecimal quantity = getDecimal(portfolioStockQuantityKey(portfolioId, changedStockId));
            if (quantity.signum() == 0) {
                result = ProfitWorkerMetrics.RESULT_SUCCESS;
                BigDecimal currentValue = getPortfolioCurrentValue(portfolioId);
                return new PortfolioCurrentValueUpdate(currentValue, currentValue, BigDecimal.ZERO);
            }

            String stockCurrentValueKey = portfolioStockCurrentValueKey(portfolioId, changedStockId);
            BigDecimal oldStockCurrentValue = getDecimal(stockCurrentValueKey);
            BigDecimal newStockCurrentValue = BigDecimal.valueOf(newPrice).multiply(quantity);
            BigDecimal delta = newStockCurrentValue.subtract(oldStockCurrentValue);

            BigDecimal nextPortfolioCurrentValue = BigDecimal.valueOf(
                    hashIncrementFloat(portfolioHashKey(portfolioId), PRECISE_CURRENT_VALUE_FIELD, delta.doubleValue()));
            BigDecimal previousPortfolioCurrentValue = nextPortfolioCurrentValue.subtract(delta);
            setDecimal(stockCurrentValueKey, newStockCurrentValue);

            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return new PortfolioCurrentValueUpdate(previousPortfolioCurrentValue, nextPortfolioCurrentValue, delta);
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
        hashIncrementFloat(portfolioHashKey(portfolioId), PRECISE_CURRENT_VALUE_FIELD, amount.doubleValue());
    }

    @Override
    public void subtractCurrentValue(Long portfolioId, BigDecimal amount) {
        hashIncrementFloat(portfolioHashKey(portfolioId), PRECISE_CURRENT_VALUE_FIELD, -amount.doubleValue());
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
    public Map<Long, PortfolioMetadata> bulkFetchPortfolioMetadata(List<Long> portfolioIds) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            List<Object> results = redisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
                var hashCommands = connection.hashCommands();
                for (Long portfolioId : portfolioIds) {
                    byte[] key = redisTemplate.getStringSerializer().serialize(portfolioHashKey(portfolioId));
                    hashCommands.hMGet(key,
                            redisTemplate.getStringSerializer().serialize("pv"),
                            redisTemplate.getStringSerializer().serialize("ac"),
                            redisTemplate.getStringSerializer().serialize("u"),
                            redisTemplate.getStringSerializer().serialize("cvp"),
                            redisTemplate.getStringSerializer().serialize("cv"));
                }
                return null;
            });

            validatePipelineResultCount("bulkFetchPortfolioMetadata", portfolioIds.size(), results.size());

            Map<Long, PortfolioMetadata> metadataMap = new HashMap<>();
            for (int i = 0; i < portfolioIds.size(); i++) {
                @SuppressWarnings("unchecked")
                List<Object> fields = (List<Object>) results.get(i);
                Long pv = parseNullableLong(fields.get(0));
                Long ac = parseNullableLong(fields.get(1));
                String userId = fields.get(2) == null ? null : fields.get(2).toString();
                BigDecimal cv;
                if (fields.get(3) != null) {
                    cv = new BigDecimal(fields.get(3).toString());
                } else if (fields.get(4) != null) {
                    cv = BigDecimal.valueOf(Long.parseLong(fields.get(4).toString()));
                } else {
                    cv = BigDecimal.ZERO;
                }
                metadataMap.put(portfolioIds.get(i), new PortfolioMetadata(pv, ac, userId, cv));
            }
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return metadataMap;
        } finally {
            metrics.recordRedisCommandDuration("pipeline_hmget_portfolio", result, sample);
        }
    }

    @Override
    public Map<String, StockHolding> bulkFetchStockHoldings(List<StockHoldingKey> tasks) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            // Pipeline: GET quantity + GET current-value for each task (2 commands per task)
            List<Object> results = redisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
                var stringCommands = connection.stringCommands();
                for (StockHoldingKey task : tasks) {
                    stringCommands.get(redisTemplate.getStringSerializer().serialize(
                            portfolioStockQuantityKey(task.portfolioId(), task.stockId())));
                    stringCommands.get(redisTemplate.getStringSerializer().serialize(
                            portfolioStockCurrentValueKey(task.portfolioId(), task.stockId())));
                }
                return null;
            });

            validatePipelineResultCount("bulkFetchStockHoldings", tasks.size() * 2, results.size());

            Map<String, StockHolding> holdings = new HashMap<>();
            for (int i = 0; i < tasks.size(); i++) {
                Object quantityRaw = results.get(i * 2);
                Object currentValueRaw = results.get(i * 2 + 1);
                BigDecimal quantity = quantityRaw == null ? BigDecimal.ZERO : new BigDecimal(quantityRaw.toString());
                BigDecimal currentValue = currentValueRaw == null ? BigDecimal.ZERO : new BigDecimal(currentValueRaw.toString());
                holdings.put(tasks.get(i).toKey(), new StockHolding(quantity, currentValue));
            }
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return holdings;
        } finally {
            metrics.recordRedisCommandDuration("pipeline_get_stock_holdings", result, sample);
        }
    }

    @Override
    public Map<Long, BigDecimal> bulkIncrementCurrentValues(Map<Long, BigDecimal> deltasByPortfolioId) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            List<Long> portfolioIds = new ArrayList<>(deltasByPortfolioId.keySet());
            List<Object> results = redisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
                var hashCommands = connection.hashCommands();
                byte[] fieldBytes = redisTemplate.getStringSerializer().serialize(PRECISE_CURRENT_VALUE_FIELD);
                for (Long portfolioId : portfolioIds) {
                    byte[] key = redisTemplate.getStringSerializer().serialize(portfolioHashKey(portfolioId));
                    BigDecimal delta = deltasByPortfolioId.get(portfolioId);
                    hashCommands.hIncrBy(key, fieldBytes, delta.doubleValue());
                }
                return null;
            });

            validatePipelineResultCount("bulkIncrementCurrentValues(portfolio)", portfolioIds.size(), results.size());

            Map<Long, BigDecimal> resultMap = new HashMap<>();
            for (int i = 0; i < portfolioIds.size(); i++) {
                Object raw = results.get(i);
                BigDecimal newValue = raw == null ? BigDecimal.ZERO : BigDecimal.valueOf(((Number) raw).doubleValue());
                resultMap.put(portfolioIds.get(i), newValue);
            }
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return resultMap;
        } finally {
            metrics.recordRedisCommandDuration("pipeline_hincrbyfloat_portfolio_cv", result, sample);
        }
    }

    @Override
    public Map<Long, PortfolioStateSnapshot> bulkIncrementCurrentValuesAndFetchMetadata(Map<Long, BigDecimal> deltasByPortfolioId) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            List<Long> portfolioIds = new ArrayList<>(deltasByPortfolioId.keySet());
            List<Object> results = redisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
                var hashCommands = connection.hashCommands();
                byte[] currentValueField = redisTemplate.getStringSerializer().serialize(PRECISE_CURRENT_VALUE_FIELD);
                byte[] purchasedValueField = redisTemplate.getStringSerializer().serialize("pv");
                byte[] assetCountField = redisTemplate.getStringSerializer().serialize("ac");
                byte[] userIdField = redisTemplate.getStringSerializer().serialize("u");

                for (Long portfolioId : portfolioIds) {
                    byte[] key = redisTemplate.getStringSerializer().serialize(portfolioHashKey(portfolioId));
                    BigDecimal delta = deltasByPortfolioId.get(portfolioId);
                    hashCommands.hIncrBy(key, currentValueField, delta.doubleValue());
                    hashCommands.hMGet(key, purchasedValueField, assetCountField, userIdField);
                }
                return null;
            });

            validatePipelineResultCount("bulkIncrementCurrentValuesAndFetchMetadata(portfolio)", portfolioIds.size() * 2, results.size());

            Map<Long, PortfolioStateSnapshot> resultMap = new HashMap<>();
            for (int i = 0; i < portfolioIds.size(); i++) {
                Object currentValueRaw = results.get(i * 2);
                @SuppressWarnings("unchecked")
                List<Object> metadataFields = (List<Object>) results.get(i * 2 + 1);

                BigDecimal currentValue = currentValueRaw == null
                        ? BigDecimal.ZERO
                        : BigDecimal.valueOf(((Number) currentValueRaw).doubleValue());
                PortfolioMetadata metadata = new PortfolioMetadata(
                        parseNullableLong(metadataFields.get(0)),
                        parseNullableLong(metadataFields.get(1)),
                        metadataFields.get(2) == null ? null : metadataFields.get(2).toString(),
                        currentValue
                );
                resultMap.put(portfolioIds.get(i), new PortfolioStateSnapshot(currentValue, metadata));
            }

            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return resultMap;
        } finally {
            metrics.recordRedisCommandDuration("pipeline_hincrbyfloat_hmget_portfolio", result, sample);
        }
    }

    @Override
    public Map<Long, List<Long>> bulkFindPortfolioIdsByStockIds(List<Long> stockIds) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            List<Object> results = redisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
                var setCommands = connection.setCommands();
                for (Long stockId : stockIds) {
                    setCommands.sMembers(redisTemplate.getStringSerializer().serialize(stockPortfoliosKey(stockId)));
                }
                return null;
            });

            validatePipelineResultCount("bulkFindPortfolioIdsByStockIds", stockIds.size(), results.size());

            Map<Long, List<Long>> resultMap = new HashMap<>();
            for (int i = 0; i < stockIds.size(); i++) {
                @SuppressWarnings("unchecked")
                Set<String> rawMembers = (Set<String>) results.get(i);
                if (rawMembers == null || rawMembers.isEmpty()) {
                    resultMap.put(stockIds.get(i), List.of());
                    continue;
                }
                List<Long> portfolioIds = rawMembers.stream()
                        .map(Long::valueOf)
                        .toList();
                resultMap.put(stockIds.get(i), portfolioIds);
            }
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return resultMap;
        } finally {
            metrics.recordRedisCommandDuration("pipeline_smembers_reverse_index", result, sample);
        }
    }

    @Override
    public String stockCurrentValueKey(Long portfolioId, Long stockId) {
        return portfolioStockCurrentValueKey(portfolioId, stockId);
    }

    @Override
    public void bulkSetStockCurrentValues(Map<String, BigDecimal> updates) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            redisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
                var stringCommands = connection.stringCommands();
                updates.forEach((key, value) -> stringCommands.set(
                        redisTemplate.getStringSerializer().serialize(key),
                        redisTemplate.getStringSerializer().serialize(toPlainString(value))));
                return null;
            });
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordRedisCommandDuration("pipeline_set_stock_cvs", result, sample);
        }
    }

    private Long parseNullableLong(Object value) {
        if (value == null) return 0L;
        return Long.valueOf(value.toString());
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

    private Double hashIncrementFloat(String key, String field, double delta) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            Double value = redisTemplate.opsForHash().increment(key, field, delta);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return value;
        } finally {
            metrics.recordRedisCommandDuration("hash_increment_float", result, sample);
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

    private void validatePipelineResultCount(String operation, int expected, int actual) {
        if (expected != actual) {
            throw new PipelineResultMismatchException(operation, expected, actual);
        }
    }
}
