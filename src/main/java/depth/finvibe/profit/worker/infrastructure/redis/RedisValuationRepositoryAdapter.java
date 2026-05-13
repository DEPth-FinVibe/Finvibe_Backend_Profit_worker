package depth.finvibe.profit.worker.infrastructure.redis;

import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
@RequiredArgsConstructor
public class RedisValuationRepositoryAdapter implements ValuationRepository {

    private final StringRedisTemplate redisTemplate;

    @Override
    public void savePortfolioValuation(PortfolioValuation valuation) {
        Long portfolioId = valuation.getPortfolioId();

        set(portfolioPurchasedValueKey(portfolioId), valuation.getPurchasedValue());
        set(portfolioCurrentValueKey(portfolioId), valuation.getCurrentValue());
        set(portfolioProfitRateKey(portfolioId), valuation.getProfitRate());
        set(portfolioAssetCountKey(portfolioId), valuation.getAssetCount());
        set(portfolioUpdatedAtKey(portfolioId), Instant.now());
        redisTemplate.opsForSet().add(dirtyPortfolioValuationsKey(), String.valueOf(portfolioId));
    }

    @Override
    public void saveUserValuation(UserValuation valuation) {
        String userId = valuation.getUserId();

        set(userPurchasedValueKey(userId), valuation.getPurchasedValue());
        set(userCurrentValueKey(userId), valuation.getCurrentValue());
        set(userProfitRateKey(userId), valuation.getProfitRate());
        set(userPortfolioCountKey(userId), valuation.getPortfolioCount());
        set(userUpdatedAtKey(userId), Instant.now());
        redisTemplate.opsForSet().add(dirtyUserValuationsKey(), String.valueOf(userId));
    }

    private void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, String.valueOf(value));
    }

    private String dirtyPortfolioValuationsKey() {
        return "dirty:portfolio-valuations";
    }

    private String dirtyUserValuationsKey() {
        return "dirty:user-valuations";
    }

    private String portfolioPurchasedValueKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":purchased-value";
    }

    private String portfolioCurrentValueKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":current-value";
    }

    private String portfolioProfitRateKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":profit-rate";
    }

    private String portfolioAssetCountKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":asset-count";
    }

    private String portfolioUpdatedAtKey(Long portfolioId) {
        return "portfolio:" + portfolioId + ":updated-at";
    }

    private String userPurchasedValueKey(String userId) {
        return "user:" + userId + ":purchased-value";
    }

    private String userCurrentValueKey(String userId) {
        return "user:" + userId + ":current-value";
    }

    private String userProfitRateKey(String userId) {
        return "user:" + userId + ":profit-rate";
    }

    private String userPortfolioCountKey(String userId) {
        return "user:" + userId + ":portfolio-count";
    }

    private String userUpdatedAtKey(String userId) {
        return "user:" + userId + ":updated-at";
    }
}
