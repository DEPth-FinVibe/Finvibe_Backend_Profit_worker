package depth.finvibe.profit.worker.consumer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import depth.finvibe.profit.worker.consumer.dto.StockPriceUpdatedEvent;
import depth.finvibe.profit.worker.service.PriceCoalescingBuffer;

@Slf4j
@Component
@RequiredArgsConstructor
public class StockPriceUpdatedKafkaConsumer {

	private final PriceCoalescingBuffer priceCoalescingBuffer;

	@KafkaListener(topics = "market.stock-price-updated.v1", groupId = "profit-worker")
	public void consume(StockPriceUpdatedEvent event) {
		if (event == null || event.getStockId() == null || event.getPrice() == null) {
			log.warn("Received invalid stock price event: {}", event);
			return;
		}

		priceCoalescingBuffer.put(event);
		log.debug("Buffered price event: stockId={}, price={}", event.getStockId(), event.getPrice());
	}
}
