package depth.finvibe.profit.worker.redis;

import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StockHoldingIndexRedisRepository {

	private static final String KEY_PREFIX = "stock:holding:";
	private static final String KEY_SUFFIX = ":portfolios";

	private final StringRedisTemplate redisTemplate;

	public Set<Long> getPortfolioIds(Long stockId) {
		Set<String> members = redisTemplate.opsForSet().members(key(stockId));
		if (members == null || members.isEmpty()) {
			return Collections.emptySet();
		}
		return members.stream()
				.map(Long::valueOf)
				.collect(Collectors.toSet());
	}

	private String key(Long stockId) {
		return KEY_PREFIX + stockId + KEY_SUFFIX;
	}
}
