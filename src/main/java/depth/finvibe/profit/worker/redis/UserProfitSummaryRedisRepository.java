package depth.finvibe.profit.worker.redis;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class UserProfitSummaryRedisRepository {

	private static final String KEY_PREFIX = "user:profit-summary:";

	private static final String FIELD_TOTAL_CURRENT_VALUE = "tcv";
	private static final String FIELD_TOTAL_PROFIT_LOSS = "tpl";
	private static final String FIELD_RETURN_RATE = "rr";
	private static final String FIELD_CALCULATED_AT = "at";

	private final StringRedisTemplate redisTemplate;

	public void update(UUID userId, BigDecimal totalCurrentValue, BigDecimal totalProfitLoss, BigDecimal returnRate) {
		Map<String, String> fields = Map.of(
				FIELD_TOTAL_CURRENT_VALUE, totalCurrentValue.toPlainString(),
				FIELD_TOTAL_PROFIT_LOSS, totalProfitLoss.toPlainString(),
				FIELD_RETURN_RATE, returnRate.toPlainString(),
				FIELD_CALCULATED_AT, LocalDateTime.now().toString()
		);
		redisTemplate.opsForHash().putAll(key(userId), fields);
	}

	private String key(UUID userId) {
		return KEY_PREFIX + userId;
	}
}
