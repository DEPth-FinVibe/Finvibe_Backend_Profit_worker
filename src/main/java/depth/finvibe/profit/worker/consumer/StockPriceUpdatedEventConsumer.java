package depth.finvibe.profit.worker.consumer;

import depth.finvibe.profit.worker.application.port.in.ProfitUseCase;
import depth.finvibe.profit.worker.consumer.dto.StockPriceUpdatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "worker.realtime-profit-update", name = "enabled", havingValue = "true")
public class StockPriceUpdatedEventConsumer {
    private final ProfitUseCase profitUseCase;

    @KafkaListener(topics = "${worker.kafka.topics.stock-price-updated:market.stock-price-updated.v1}")
    public void consume(StockPriceUpdatedEvent event) {
        profitUseCase.updateProfits(event.toProfitRecalculateRequest());
    }
}
