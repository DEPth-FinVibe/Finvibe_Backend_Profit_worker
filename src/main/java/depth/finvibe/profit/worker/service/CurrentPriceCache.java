package depth.finvibe.profit.worker.service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Worker 프로세스 내 현재가 인메모리 캐시.
 * Kafka 이벤트로 수신한 최신 가격을 보관하며, 수익률 계산 시 참조한다.
 * Worker 재시작 시 초기화되지만, 다음 가격 이벤트 수신 시 자연 복구된다.
 */
@Component
public class CurrentPriceCache {

	private final ConcurrentHashMap<Long, BigDecimal> prices = new ConcurrentHashMap<>();

	public void put(Long stockId, BigDecimal price) {
		prices.put(stockId, price);
	}

	public BigDecimal get(Long stockId) {
		return prices.get(stockId);
	}
}
