package depth.finvibe.profit.worker.service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import depth.finvibe.profit.worker.consumer.dto.StockPriceUpdatedEvent;

/**
 * 동일 종목의 가격 이벤트를 coalescing하는 버퍼.
 * 10초 윈도우 내에서 같은 stockId의 이벤트는 마지막 가격만 유지한다.
 */
public class PriceCoalescingBuffer {

	private final ConcurrentHashMap<Long, StockPriceUpdatedEvent> buffer = new ConcurrentHashMap<>();

	public void put(StockPriceUpdatedEvent event) {
		buffer.merge(event.getStockId(), event, (existing, incoming) -> {
			if (incoming.getUpdatedAt() != null && existing.getUpdatedAt() != null
					&& incoming.getUpdatedAt().isAfter(existing.getUpdatedAt())) {
				return incoming;
			}
			return incoming; // updatedAt이 null이면 최신 이벤트 우선
		});
	}

	/**
	 * 버퍼를 비우고 현재까지 쌓인 이벤트를 반환한다.
	 */
	public Map<Long, StockPriceUpdatedEvent> drainAll() {
		Map<Long, StockPriceUpdatedEvent> snapshot = new HashMap<>(buffer);
		buffer.keySet().removeAll(snapshot.keySet());
		return snapshot;
	}

	public boolean isEmpty() {
		return buffer.isEmpty();
	}
}
