package depth.finvibe.profit.worker.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import depth.finvibe.profit.worker.application.port.in.ProfitCalculationUseCase;
import depth.finvibe.profit.worker.dto.ProfitCalculationDto;
import depth.finvibe.profit.worker.infrastructure.kafka.dto.StockPriceUpdatedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

@Component
@RequiredArgsConstructor
public class StockPriceEventConsumer {

    private final ProfitCalculationUseCase profitCalculationUseCase;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @KafkaListener(topics = "${app.kafka.topics.stock-price-updated:market.stock-price-updated.v1}")
    public void consumeStockPriceUpdatedEvent(String payload) {
        StockPriceUpdatedEvent event = read(payload, StockPriceUpdatedEvent.class);

        profitCalculationUseCase.updateProfitByStockPriceChange(ProfitCalculationDto.ProfitCalculationRequest.builder()
                .stockId(event.getStockId())
                .newPrice(toPrice(event))
                .timestamp(event.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant())
                .build());
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid Kafka event payload", e);
        }
    }

    private Long toPrice(StockPriceUpdatedEvent event) {
        try {
            return event.getPrice().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Stock price event price must be an integer: " + event.getPrice(), e);
        }
    }
}
