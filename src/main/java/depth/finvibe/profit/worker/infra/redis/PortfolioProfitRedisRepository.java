package depth.finvibe.profit.worker.infra.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PortfolioProfitRedisRepository {
    private static final String PORTFOLIO_KEY_PREFIX = "profit:portfolio";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> updatePortfolioProfitScript;

    public PortfolioProfitRedisRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/update-portfolio-profit.lua")));
        script.setResultType(List.class);
        this.updatePortfolioProfitScript = script;
    }

    public PortfolioProfitUpdateResult updateByStockPrice(Long portfolioId, Long stockId, Double newPrice) {
        List<String> keys = List.of(portfolioKeyOf(portfolioId), holdingKeyOf(portfolioId, stockId));
        List<?> result = redisTemplate.execute(updatePortfolioProfitScript, keys, newPrice.toString());
        if (result == null || result.size() != 4) {
            throw new IllegalStateException("Failed to update portfolio profit: portfolioId=" + portfolioId + ", stockId=" + stockId);
        }

        return new PortfolioProfitUpdateResult(
                parseDouble(result.get(0)),
                parseDouble(result.get(1)),
                parseDouble(result.get(2)),
                parseDouble(result.get(3))
        );
    }

    private String portfolioKeyOf(Long portfolioId) {
        return PORTFOLIO_KEY_PREFIX + ":" + portfolioId;
    }

    private String holdingKeyOf(Long portfolioId, Long stockId) {
        return PORTFOLIO_KEY_PREFIX + ":" + portfolioId + ":stock:" + stockId;
    }

    private Double parseDouble(Object value) {
        return Double.valueOf(value.toString());
    }
}
