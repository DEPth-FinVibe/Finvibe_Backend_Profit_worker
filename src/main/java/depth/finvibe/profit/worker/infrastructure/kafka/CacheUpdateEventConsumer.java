package depth.finvibe.profit.worker.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import depth.finvibe.profit.worker.application.port.in.CacheUpdateUseCase;
import depth.finvibe.profit.worker.dto.CacheUpdateDto;
import depth.finvibe.profit.worker.infrastructure.kafka.dto.PortfolioTradeEvent;
import depth.finvibe.profit.worker.infrastructure.kafka.dto.PortfolioUserEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CacheUpdateEventConsumer {

    private final CacheUpdateUseCase cacheUpdateUseCase;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @KafkaListener(topics = "${app.kafka.topics.portfolio-trade:trade.trade-executed.v1}")
    public void consumePortfolioTradeEvent(String payload) {
        PortfolioTradeEvent event = read(payload, PortfolioTradeEvent.class);

        cacheUpdateUseCase.updatePortfolioCache(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                .portfolioId(event.getPortfolioId())
                .stockId(event.getStockId())
                .type(toTradeType(event.getType()))
                .price(event.getPrice())
                .quantity(toQuantity(event))
                .build());
    }

    @KafkaListener(topics = "${app.kafka.topics.portfolio-user:asset.portfolio-group-changed.v1}")
    public void consumePortfolioUserEvent(String payload) {
        PortfolioUserEvent event = read(payload, PortfolioUserEvent.class);

        if (event.getEventType() == PortfolioUserEvent.EventType.UPDATED) {
            return;
        }

        cacheUpdateUseCase.updateUserCache(CacheUpdateDto.UserCacheUpdateRequest.builder()
                .userId(event.getUserId())
                .portfolioId(event.getPortfolioId())
                .type(toChangeType(event.getEventType()))
                .build());
    }

    private <T> T read(String payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid Kafka event payload", e);
        }
    }

    private CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType toTradeType(String type) {
        return switch (type) {
            case "BUY" -> CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_BUY;
            case "SELL" -> CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_SELL;
            default -> throw new IllegalArgumentException("Unsupported trade event type: " + type);
        };
    }

    private Long toQuantity(PortfolioTradeEvent event) {
        try {
            return event.getAmount().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Trade event amount must be an integer: " + event.getAmount(), e);
        }
    }

    private CacheUpdateDto.UserCacheUpdateRequest.ChangeType toChangeType(PortfolioUserEvent.EventType eventType) {
        return switch (eventType) {
            case CREATED -> CacheUpdateDto.UserCacheUpdateRequest.ChangeType.CREATED;
            case DELETED -> CacheUpdateDto.UserCacheUpdateRequest.ChangeType.DELETED;
            case UPDATED -> throw new IllegalArgumentException("Updated portfolio event does not change user cache");
        };
    }
}
