package depth.finvibe.profit.worker.infrastructure.redis;

import depth.finvibe.profit.worker.application.ProfitWorkerMetrics;
import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisUserStateStore implements UserStateStore {

    private final StringRedisTemplate redisTemplate;
    private final ProfitWorkerMetrics metrics;

    @Override
    public String findUserIdByPortfolioId(Long portfolioId) {
        Object value = hashGet(portfolioHashKey(portfolioId), "u");
        return value == null ? null : value.toString();
    }

    @Override
    public Long findPurchasedValue(String userId) {
        return getHashLong(userHashKey(userId), "pv");
    }

    @Override
    public BigDecimal calculateCurrentValue(String userId) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;

        try {
            Set<String> portfolioIds = setMembers(userPortfoliosKey(userId));
            if (portfolioIds == null) {
                result = ProfitWorkerMetrics.RESULT_SUCCESS;
                return BigDecimal.ZERO;
            }

            BigDecimal currentValue = portfolioIds.stream()
                    .map(Long::valueOf)
                    .map(this::getPortfolioCurrentValue)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
            return currentValue;
        } finally {
            metrics.recordRedisDuration(ProfitWorkerMetrics.OPERATION_USER_CURRENT_VALUE, result, sample);
        }
    }

    @Override
    public BigDecimal findCurrentValue(String userId) {
        return getUserCurrentValue(userId);
    }

    @Override
    public BigDecimal addCurrentValue(String userId, BigDecimal delta) {
        Double nextValue = hashIncrementFloat(userHashKey(userId), "cvp", delta.doubleValue());
        return BigDecimal.valueOf(nextValue);
    }

    @Override
    public Long findPortfolioCount(String userId) {
        return getHashLong(userHashKey(userId), "pc");
    }

    @Override
    public void mapPortfolioToUser(Long portfolioId, String userId) { // 추후 정수 기반 UserID로 변경
        redisTemplate.opsForHash().put(portfolioHashKey(portfolioId), "u", String.valueOf(userId));
        redisTemplate.opsForSet().add(userPortfoliosKey(userId), String.valueOf(portfolioId));
    }

    @Override
    public void removePortfolioUserMapping(Long portfolioId) {
        String userId = findUserIdByPortfolioId(portfolioId);
        redisTemplate.opsForHash().delete(portfolioHashKey(portfolioId), "u");

        if (userId != null) {
            redisTemplate.opsForSet().remove(userPortfoliosKey(userId), String.valueOf(portfolioId));
        }
    }

    @Override
    public void addPurchasedValue(String userId, Long amount) {
        incrementHash(userHashKey(userId), "pv", amount);
    }

    @Override
    public void subtractPurchasedValue(String userId, Long amount) {
        incrementHash(userHashKey(userId), "pv", -amount);
    }

    @Override
    public void increasePortfolioCount(String userId) {
        incrementHash(userHashKey(userId), "pc", 1L);
    }

    @Override
    public void decreasePortfolioCount(String userId) {
        incrementHash(userHashKey(userId), "pc", -1L);
    }

    private Long getHashLong(String key, String field) {
        Object value = hashGet(key, field);
        if (value == null) {
            return 0L;
        }
        return Long.valueOf(value.toString());
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
        Object preciseValue = hashGet(key, "cvp");
        if (preciseValue != null) {
            return new BigDecimal(preciseValue.toString());
        }
        return BigDecimal.valueOf(getHashLong(key, "cv"));
    }

    private BigDecimal getUserCurrentValue(String userId) {
        String key = userHashKey(userId);
        Object preciseValue = hashGet(key, "cvp");
        if (preciseValue != null) {
            return new BigDecimal(preciseValue.toString());
        }
        return BigDecimal.valueOf(getHashLong(key, "cv"));
    }

    private void setHashDecimal(String key, String field, BigDecimal value) {
        hashPut(key, field, value.toPlainString());
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

    private Set<String> setMembers(String key) {
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

    private String portfolioHashKey(Long portfolioId) {
        return "pf:" + portfolioId;
    }

    private String userHashKey(String userId) {
        return "usr:" + userId;
    }

    private String userPortfoliosKey(String userId) {
        return "user:" + userId + ":portfolios";
    }
}
