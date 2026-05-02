package depth.finvibe.profit.worker.infra.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

@RequiredArgsConstructor
@Repository
public class PortfolioUserOwnershipRedisRepository {
    private final String KEY_PREFIX = "portfolio-user-ownership";

    private final RedisTemplate<String, Long> redisTemplate;

    private void registerPortfolio(Long portfolioId, Long userId) {
        redisTemplate.opsForValue().set(keyOf(portfolioId), userId);
    }

    private void unregisterPortfolio(Long portfolioId) {
        redisTemplate.opsForValue().getAndDelete(keyOf(portfolioId));
    }

    private String keyOf(Long userId) {
        return KEY_PREFIX + ":" + userId.toString();
    }
}
