package depth.finvibe.profit.worker.infrastructure.kafka;

import depth.finvibe.profit.worker.application.port.in.CacheUpdateUseCase;
import depth.finvibe.profit.worker.dto.CacheUpdateDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class CacheUpdateEventConsumerTest {

    @Test
    void consumesMonolithTradeExecutedEvent() {
        CacheUpdateUseCase cacheUpdateUseCase = mock(CacheUpdateUseCase.class);
        CacheUpdateEventConsumer consumer = new CacheUpdateEventConsumer(cacheUpdateUseCase);

        consumer.consumePortfolioTradeEvent("""
                {
                  "tradeId": 1,
                  "userId": "7a22103f-1d1c-4ab4-9c47-4040c3a46964",
                  "type": "BUY",
                  "amount": 10,
                  "price": 50000,
                  "stockId": 123,
                  "name": "Samsung Electronics",
                  "currency": "KRW",
                  "portfolioId": 456
                }
                """);

        ArgumentCaptor<CacheUpdateDto.PortfolioCacheUpdateRequest> captor =
                ArgumentCaptor.forClass(CacheUpdateDto.PortfolioCacheUpdateRequest.class);
        verify(cacheUpdateUseCase).updatePortfolioCache(captor.capture());

        CacheUpdateDto.PortfolioCacheUpdateRequest request = captor.getValue();
        assertThat(request.getPortfolioId()).isEqualTo(456L);
        assertThat(request.getStockId()).isEqualTo(123L);
        assertThat(request.getType()).isEqualTo(CacheUpdateDto.PortfolioCacheUpdateRequest.TradeType.STOCK_BUY);
        assertThat(request.getPrice()).isEqualTo(50000L);
        assertThat(request.getQuantity()).isEqualTo(10L);
    }

    @Test
    void rejectsFractionalTradeAmount() {
        CacheUpdateUseCase cacheUpdateUseCase = mock(CacheUpdateUseCase.class);
        CacheUpdateEventConsumer consumer = new CacheUpdateEventConsumer(cacheUpdateUseCase);

        assertThatThrownBy(() -> consumer.consumePortfolioTradeEvent("""
                {
                  "type": "SELL",
                  "amount": 10.5,
                  "price": 50000,
                  "stockId": 123,
                  "portfolioId": 456
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must be an integer");
        verifyNoInteractions(cacheUpdateUseCase);
    }

    @Test
    void consumesPortfolioGroupCreatedEvent() {
        CacheUpdateUseCase cacheUpdateUseCase = mock(CacheUpdateUseCase.class);
        CacheUpdateEventConsumer consumer = new CacheUpdateEventConsumer(cacheUpdateUseCase);

        consumer.consumePortfolioUserEvent("""
                {
                  "eventType": "CREATED",
                  "userId": "7a22103f-1d1c-4ab4-9c47-4040c3a46964",
                  "portfolioId": 456,
                  "occurredAt": "2026-05-13T13:00:00Z"
                }
                """);

        ArgumentCaptor<CacheUpdateDto.UserCacheUpdateRequest> captor =
                ArgumentCaptor.forClass(CacheUpdateDto.UserCacheUpdateRequest.class);
        verify(cacheUpdateUseCase).updateUserCache(captor.capture());

        CacheUpdateDto.UserCacheUpdateRequest request = captor.getValue();
        assertThat(request.getUserId()).isEqualTo("7a22103f-1d1c-4ab4-9c47-4040c3a46964");
        assertThat(request.getPortfolioId()).isEqualTo(456L);
        assertThat(request.getType()).isEqualTo(CacheUpdateDto.UserCacheUpdateRequest.ChangeType.CREATED);
    }

    @Test
    void ignoresPortfolioGroupUpdatedEvent() {
        CacheUpdateUseCase cacheUpdateUseCase = mock(CacheUpdateUseCase.class);
        CacheUpdateEventConsumer consumer = new CacheUpdateEventConsumer(cacheUpdateUseCase);

        consumer.consumePortfolioUserEvent("""
                {
                  "eventType": "UPDATED",
                  "userId": "7a22103f-1d1c-4ab4-9c47-4040c3a46964",
                  "portfolioId": 456,
                  "occurredAt": "2026-05-13T13:00:00Z"
                }
                """);

        verifyNoInteractions(cacheUpdateUseCase);
    }
}
