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
import java.util.List;
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

    @Override
    public void bulkSavePortfolioValuations(List<PortfolioValuation> valuations) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            executePortfolioValuationPipeline(
                    valuations,
                    false,
                    "bulkSavePortfolioValuations"
            );
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordRedisCommandDuration("pipeline_save_portfolio_valuations", result, sample);
        }
    }

    @Override
    public void bulkSavePortfolioPriceUpdateValuations(List<PortfolioValuation> valuations) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            executePortfolioValuationPipeline(
                    valuations,
                    true,
                    "bulkSavePortfolioPriceUpdateValuations"
            );
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordRedisCommandDuration("pipeline_save_portfolio_price_updates", result, sample);
        }
    }

    @Override
    public void bulkSaveUserValuations(List<UserValuation> valuations) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            executeUserValuationPipeline(
                    valuations,
                    false,
                    "bulkSaveUserValuations"
            );
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordRedisCommandDuration("pipeline_save_user_valuations", result, sample);
        }
    }

    @Override
    public void bulkSaveUserPriceUpdateValuations(List<UserValuation> valuations) {
        Timer.Sample sample = metrics.startSample();
        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            executeUserValuationPipeline(
                    valuations,
                    true,
                    "bulkSaveUserPriceUpdateValuations"
            );
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            metrics.recordRedisCommandDuration("pipeline_save_user_price_updates", result, sample);
        }
    }

    private void executePortfolioValuationPipeline(
            List<PortfolioValuation> valuations,
            boolean priceUpdateOnly,
            String operation
    ) {
        Instant updatedAt = Instant.now();
        String updatedAtStr = updatedAt.toString();
        String dirtyKey = dirtyPortfolioValuationsKey();

        List<Object> results = redisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
            var hashCommands = connection.hashCommands();
            var setCommands = connection.setCommands();
            byte[] dirtyKeyBytes = redisTemplate.getStringSerializer().serialize(dirtyKey);

            for (PortfolioValuation v : valuations) {
                byte[] key = redisTemplate.getStringSerializer().serialize(portfolioHashKey(v.getPortfolioId()));
                hashCommands.hMSet(key, portfolioFields(v, updatedAtStr, priceUpdateOnly));
                setCommands.sAdd(dirtyKeyBytes, redisTemplate.getStringSerializer().serialize(String.valueOf(v.getPortfolioId())));
            }
            return null;
        });

        validatePipelineResultCount(operation, valuations.size(), results.size());
    }

    private void executeUserValuationPipeline(
            List<UserValuation> valuations,
            boolean priceUpdateOnly,
            String operation
    ) {
        Instant updatedAt = Instant.now();
        String updatedAtStr = updatedAt.toString();
        String dirtyKey = dirtyUserValuationsKey();

        List<Object> results = redisTemplate.executePipelined((org.springframework.data.redis.connection.RedisConnection connection) -> {
            var hashCommands = connection.hashCommands();
            var setCommands = connection.setCommands();
            byte[] dirtyKeyBytes = redisTemplate.getStringSerializer().serialize(dirtyKey);

            for (UserValuation v : valuations) {
                byte[] key = redisTemplate.getStringSerializer().serialize(userHashKey(v.getUserId()));
                hashCommands.hMSet(key, userFields(v, updatedAtStr, priceUpdateOnly));
                setCommands.sAdd(dirtyKeyBytes, redisTemplate.getStringSerializer().serialize(v.getUserId()));
            }
            return null;
        });

        validatePipelineResultCount(operation, valuations.size(), results.size());
    }

    private Map<byte[], byte[]> portfolioFields(PortfolioValuation valuation, String updatedAtStr, boolean priceUpdateOnly) {
        if (priceUpdateOnly) {
            return Map.of(
                    serialize("cv"), serialize(String.valueOf(valuation.getCurrentValue())),
                    serialize("pr"), serialize(String.valueOf(valuation.getProfitRate())),
                    serialize("ua"), serialize(updatedAtStr)
            );
        }

        return Map.of(
                serialize("pv"), serialize(String.valueOf(valuation.getPurchasedValue())),
                serialize("cv"), serialize(String.valueOf(valuation.getCurrentValue())),
                serialize("pr"), serialize(String.valueOf(valuation.getProfitRate())),
                serialize("ac"), serialize(String.valueOf(valuation.getAssetCount())),
                serialize("del"), serialize("0"),
                serialize("ua"), serialize(updatedAtStr)
        );
    }

    private Map<byte[], byte[]> userFields(UserValuation valuation, String updatedAtStr, boolean priceUpdateOnly) {
        if (priceUpdateOnly) {
            return Map.of(
                    serialize("cv"), serialize(String.valueOf(valuation.getCurrentValue())),
                    serialize("pr"), serialize(String.valueOf(valuation.getProfitRate())),
                    serialize("ua"), serialize(updatedAtStr)
            );
        }

        return Map.of(
                serialize("pv"), serialize(String.valueOf(valuation.getPurchasedValue())),
                serialize("cv"), serialize(String.valueOf(valuation.getCurrentValue())),
                serialize("pr"), serialize(String.valueOf(valuation.getProfitRate())),
                serialize("pc"), serialize(String.valueOf(valuation.getPortfolioCount())),
                serialize("ua"), serialize(updatedAtStr)
        );
    }

    private byte[] serialize(String value) {
        return redisTemplate.getStringSerializer().serialize(value);
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

    private void validatePipelineResultCount(String operation, int expected, int actual) {
        if (expected != actual) {
            throw new PipelineResultMismatchException(operation, expected, actual);
        }
    }
}
