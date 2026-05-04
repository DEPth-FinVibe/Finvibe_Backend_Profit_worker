package depth.finvibe.profit.worker.infra.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class PortfolioUserOwnershipRedisRepository {
    private static final String KEY_PREFIX = "profit:portfolio-user";

    private final StringRedisTemplate redisTemplate;

    public void registerPortfolio(Long portfolioId, Long userId) {
        redisTemplate.opsForValue().set(keyOf(portfolioId), userId.toString());
    }

    public void unregisterPortfolio(Long portfolioId) {
        redisTemplate.opsForValue().getAndDelete(keyOf(portfolioId));
    }

    public Long findUserIdByPortfolioId(Long portfolioId) {
        String userId = redisTemplate.opsForValue().get(keyOf(portfolioId));
        return userId == null ? null : Long.valueOf(userId);
    }

    private String keyOf(Long portfolioId) {
        return KEY_PREFIX + ":" + portfolioId;
    }
}
