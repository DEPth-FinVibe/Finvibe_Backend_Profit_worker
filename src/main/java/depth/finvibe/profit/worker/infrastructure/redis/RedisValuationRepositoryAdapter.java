package depth.finvibe.profit.worker.infrastructure.redis;

import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class RedisValuationRepositoryAdapter implements ValuationRepository {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void savePortfolioValuation(PortfolioValuation valuation) {
        Long portfolioId = valuation.getPortfolioId();
        Instant updatedAt = Instant.now();

        redisTemplate.opsForHash().putAll(portfolioHashKey(portfolioId), Map.of(
                "pv", String.valueOf(valuation.getPurchasedValue()),
                "cv", String.valueOf(valuation.getCurrentValue()),
                "pr", String.valueOf(valuation.getProfitRate()),
                "ac", String.valueOf(valuation.getAssetCount()),
                "del", "0",
                "ua", updatedAt.toString()
        ));
        redisTemplate.opsForSet().add(dirtyPortfolioValuationsKey(), String.valueOf(portfolioId));
    }

    @Override
    public void markPortfolioValuationDeleted(Long portfolioId) {
        Instant deletedAt = Instant.now();
        redisTemplate.opsForHash().putAll(portfolioHashKey(portfolioId), Map.of(
                "del", "1",
                "da", deletedAt.toString()
        ));
        redisTemplate.opsForSet().add(dirtyPortfolioValuationDeletionsKey(), String.valueOf(portfolioId));
    }

    @Override
    public void saveUserValuation(UserValuation valuation) {
        String userId = valuation.getUserId();
        Instant updatedAt = Instant.now();

        redisTemplate.opsForHash().putAll(userHashKey(userId), Map.of(
                "pv", String.valueOf(valuation.getPurchasedValue()),
                "cv", String.valueOf(valuation.getCurrentValue()),
                "pr", String.valueOf(valuation.getProfitRate()),
                "pc", String.valueOf(valuation.getPortfolioCount()),
                "ua", updatedAt.toString()
        ));
        redisTemplate.opsForSet().add(dirtyUserValuationsKey(), String.valueOf(userId));
    }

    private String dirtyPortfolioValuationsKey() {
        return "dirty:portfolio-valuations";
    }

    private String dirtyPortfolioValuationDeletionsKey() {
        return "dirty:portfolio-valuation-deletions";
    }

    private String dirtyUserValuationsKey() {
        return "dirty:user-valuations";
    }

    private String portfolioHashKey(Long portfolioId) {
        return "pf:" + portfolioId;
    }

    private String userHashKey(String userId) {
        return "usr:" + userId;
    }
}
