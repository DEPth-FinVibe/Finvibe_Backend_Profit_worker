package depth.finvibe.profit.worker.redis;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserProfitRankingRedisRepository {

	private static final String KEY_PREFIX = "user:profit-ranking:";

	private final StringRedisTemplate redisTemplate;

	public void updateScore(String rankType, UUID userId, double returnRate) {
		redisTemplate.opsForZSet().add(key(rankType), userId.toString(), returnRate);
	}

	private String key(String rankType) {
		return KEY_PREFIX + rankType;
	}
}
