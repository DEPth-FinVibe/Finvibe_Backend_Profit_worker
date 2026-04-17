package depth.finvibe.profit.worker.redis;

import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PortfolioOwnerRedisRepository {

	private static final String KEY_PREFIX = "portfolio:owner:";

	private final StringRedisTemplate redisTemplate;

	public UUID get(Long portfolioId) {
		String value = redisTemplate.opsForValue().get(key(portfolioId));
		if (value == null) {
			return null;
		}
		return UUID.fromString(value);
	}

	private String key(Long portfolioId) {
		return KEY_PREFIX + portfolioId;
	}
}
