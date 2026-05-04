package depth.finvibe.profit.worker.infra.redis;

import depth.finvibe.profit.worker.application.port.out.PortfolioStockOwnershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

/***
 * 특정 종목을 보유하고 있는 포트폴리오의 리스트를 CRUD
 */
@RequiredArgsConstructor
@Repository
public class PortfolioStockOwnershipRedisRepository implements PortfolioStockOwnershipRepository {
    private static final String KEY_PREFIX = "profit:stock";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void registerPortfolioTo(Long stockId, Long portfolioId) {
        redisTemplate.opsForSet().add(keyOf(stockId), portfolioId.toString());
    }

    @Override
    public void unregisterPortfolioFrom(Long stockId, Long portfolioId) {
        redisTemplate.opsForSet().remove(keyOf(stockId), portfolioId.toString());
    }

    @Override
    public Set<Long> findPortfolioIdsByStockId(Long stockId) {
        Set<String> portfolioIds = redisTemplate.opsForSet().members(keyOf(stockId));
        if (portfolioIds == null || portfolioIds.isEmpty()) {
            return Collections.emptySet();
        }

        return portfolioIds.stream()
                .map(Long::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }

    private String keyOf(Long stockId) {
        return KEY_PREFIX + ":" + stockId + ":portfolios";
    }
}
