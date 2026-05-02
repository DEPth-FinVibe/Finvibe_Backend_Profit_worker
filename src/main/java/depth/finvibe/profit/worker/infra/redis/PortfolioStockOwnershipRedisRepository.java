package depth.finvibe.profit.worker.infra.redis;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;

/***
 * 특정 종목을 보유하고 있는 포트폴리오의 리스트를 CRUD
 */
@RequiredArgsConstructor
public class PortfolioStockOwnershipRedisRepository {
    private final String KEY_PREFIX = "portfolio-stock-ownership";

    private final RedisTemplate<String, Long> redisTemplate;

    void registerPortfolioTo(Long stockId, Long portfolioId) {
        redisTemplate.opsForSet().add(keyOf(stockId), portfolioId);
    }

    void unregisterPortfolioFrom(Long stockId, Long portfolioId) {
        redisTemplate.opsForSet().remove(keyOf(stockId), portfolioId);
    }

    private String keyOf(Long stockId) {
        return KEY_PREFIX + ":" + stockId.toString();
    }
}
