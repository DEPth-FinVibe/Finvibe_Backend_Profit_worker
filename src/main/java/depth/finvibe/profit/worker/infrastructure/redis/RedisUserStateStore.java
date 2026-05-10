package depth.finvibe.profit.worker.infrastructure.redis;

import depth.finvibe.profit.worker.application.port.out.UserStateStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
@RequiredArgsConstructor
public class RedisUserStateStore implements UserStateStore {

    private final StringRedisTemplate redisTemplate;

    @Override
    public Long findUserIdByPortfolioId(Long portfolioId) {
        return getLong(portfolioUserKey(portfolioId));
    }

    @Override
    public Long findPurchasedValue(Long userId) {
        return getLong(userPurchasedValueKey(userId));
    }

    @Override
    public Long calculateCurrentValue(Long userId) {
        Set<String> portfolioIds = redisTemplate.opsForSet().members(userPortfoliosKey(userId));
        if (portfolioIds == null) {
            return 0L;
        }

        return portfolioIds.stream()
                .map(Long::valueOf)
                .mapToLong(portfolioId -> getLong(portfolioCurrentValueKey(portfolioId)))
                .sum();
    }

    @Override
    public Long findPortfolioCount(Long userId) {
        return getLong(userPortfolioCountKey(userId));
    }

    @Override
    public void mapPortfolioToUser(Long portfolioId, Long userId) {
        redisTemplate.opsForValue().set(portfolioUserKey(portfolioId), String.valueOf(userId));
        redisTemplate.opsForSet().add(userPortfoliosKey(userId), String.valueOf(portfolioId));
    }

    @Override
    public void removePortfolioUserMapping(Long portfolioId) {
        Long userId = findUserIdByPortfolioId(portfolioId);
        redisTemplate.delete(portfolioUserKey(portfolioId));

        if (userId != 0L) {
            redisTemplate.opsForSet().remove(userPortfoliosKey(userId), String.valueOf(portfolioId));
        }
    }

    @Override
    public void addPurchasedValue(Long userId, Long amount) {
        increment(userPurchasedValueKey(userId), amount);
    }

    @Override
    public void subtractPurchasedValue(Long userId, Long amount) {
        increment(userPurchasedValueKey(userId), -amount);
    }

    @Override
    public void increasePortfolioCount(Long userId) {
        increment(userPortfolioCountKey(userId), 1L);
    }

    @Override
    public void decreasePortfolioCount(Long userId) {
        increment(userPortfolioCountKey(userId), -1L);
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

    private String portfolioUserKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":user";
    }

    private String portfolioCurrentValueKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":current-value";
    }

    private String userPurchasedValueKey(Long userId) {
        return "user:" + userId + ":purchased-value";
    }

    private String userPortfolioCountKey(Long userId) {
        return "user:" + userId + ":portfolio-count";
    }

    private String userPortfoliosKey(Long userId) {
        return "user:" + userId + ":portfolios";
    }
}
