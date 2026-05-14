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
    public String findUserIdByPortfolioId(Long portfolioId) {
        Object value = redisTemplate.opsForHash().get(portfolioHashKey(portfolioId), "u");
        return value == null ? null : value.toString();
    }

    @Override
    public Long findPurchasedValue(String userId) {
        return getHashLong(userHashKey(userId), "pv");
    }

    @Override
    public Long calculateCurrentValue(String userId) {
        Set<String> portfolioIds = redisTemplate.opsForSet().members(userPortfoliosKey(userId));
        if (portfolioIds == null) {
            return 0L;
        }

        return portfolioIds.stream()
                .map(Long::valueOf)
                .mapToLong(portfolioId -> getHashLong(portfolioHashKey(portfolioId), "cv"))
                .sum();
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
        Object value = redisTemplate.opsForHash().get(key, field);
        if (value == null) {
            return 0L;
        }
        return Long.valueOf(value.toString());
    }

    private Long incrementHash(String key, String field, Long delta) {
        Long value = redisTemplate.opsForHash().increment(key, field, delta);
        if (value == null) {
            return 0L;
        }
        return value;
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
