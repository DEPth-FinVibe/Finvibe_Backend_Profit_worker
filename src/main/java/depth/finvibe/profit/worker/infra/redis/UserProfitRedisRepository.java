package depth.finvibe.profit.worker.infra.redis;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UserProfitRedisRepository {
    private static final String USER_KEY_PREFIX = "profit:user";
    private static final String USER_RETURN_RATE_RANKING_KEY = "profit:user:return-rate-ranking";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> updateUserProfitScript;

    public UserProfitRedisRepository(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource("redis/update-user-profit.lua")));
        script.setResultType(List.class);
        this.updateUserProfitScript = script;
    }

    public UserProfitUpdateResult updateReturnRateAndRanking(
            Long userId,
            Double oldPortfolioUnrealizedProfit,
            Double newPortfolioUnrealizedProfit
    ) {
        List<String> keys = List.of(userKeyOf(userId), USER_RETURN_RATE_RANKING_KEY);
        List<?> result = redisTemplate.execute(
                updateUserProfitScript,
                keys,
                userId.toString(),
                oldPortfolioUnrealizedProfit.toString(),
                newPortfolioUnrealizedProfit.toString()
        );
        if (result == null || result.size() != 4) {
            throw new IllegalStateException("Failed to update user profit: userId=" + userId);
        }

        return new UserProfitUpdateResult(
                parseDouble(result.get(0)),
                parseDouble(result.get(1)),
                parseDouble(result.get(2)),
                parseDouble(result.get(3))
        );
    }

    private String userKeyOf(Long userId) {
        return USER_KEY_PREFIX + ":" + userId;
    }

    private Double parseDouble(Object value) {
        return Double.valueOf(value.toString());
    }
}
