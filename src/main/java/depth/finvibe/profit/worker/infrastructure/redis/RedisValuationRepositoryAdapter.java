package depth.finvibe.profit.worker.infrastructure.redis;

import depth.finvibe.profit.worker.application.ProfitWorkerMetrics;
import depth.finvibe.profit.worker.application.port.out.ValuationRepository;
import depth.finvibe.profit.worker.domain.PortfolioValuation;
import depth.finvibe.profit.worker.domain.UserValuation;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class RedisValuationRepositoryAdapter implements ValuationRepository {

    private final StringRedisTemplate redisTemplate;
    private final ProfitWorkerMetrics metrics;

    @Override
    public void savePortfolioValuation(PortfolioValuation valuation) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;

        try {
            Long portfolioId = valuation.getPortfolioId();
            Instant updatedAt = Instant.now();

            hashPutAll(portfolioHashKey(portfolioId), Map.of(
                    "pv", String.valueOf(valuation.getPurchasedValue()),
                    "cv", String.valueOf(valuation.getCurrentValue()),
                    "pr", String.valueOf(valuation.getProfitRate()),
                    "ac", String.valueOf(valuation.getAssetCount()),
                    "del", "0",
                    "ua", updatedAt.toString()
            ));
            setAdd(dirtyPortfolioValuationsKey(), String.valueOf(portfolioId));
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordRedisDuration(ProfitWorkerMetrics.OPERATION_PORTFOLIO_VALUATION_SAVE, result, sample);
        }
    }

    @Override
    public void markPortfolioValuationDeleted(Long portfolioId) {
        Instant deletedAt = Instant.now();
        hashPutAll(portfolioHashKey(portfolioId), Map.of(
                "del", "1",
                "da", deletedAt.toString()
        ));
        setAdd(dirtyPortfolioValuationDeletionsKey(), String.valueOf(portfolioId));
    }

    @Override
    public void saveUserValuation(UserValuation valuation) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;

        try {
            String userId = valuation.getUserId();
            Instant updatedAt = Instant.now();

            hashPutAll(userHashKey(userId), Map.of(
                    "pv", String.valueOf(valuation.getPurchasedValue()),
                    "cv", String.valueOf(valuation.getCurrentValue()),
                    "pr", String.valueOf(valuation.getProfitRate()),
                    "pc", String.valueOf(valuation.getPortfolioCount()),
                    "ua", updatedAt.toString()
            ));
            setAdd(dirtyUserValuationsKey(), String.valueOf(userId));
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordRedisDuration(ProfitWorkerMetrics.OPERATION_USER_VALUATION_SAVE, result, sample);
        }
    }

    private void hashPutAll(String key, Map<String, String> values) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            redisTemplate.opsForHash().putAll(key, values);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordRedisCommandDuration("hash_put_all", result, sample);
        }
    }

    private void setAdd(String key, String member) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            redisTemplate.opsForSet().add(key, member);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordRedisCommandDuration("set_add", result, sample);
        }
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
