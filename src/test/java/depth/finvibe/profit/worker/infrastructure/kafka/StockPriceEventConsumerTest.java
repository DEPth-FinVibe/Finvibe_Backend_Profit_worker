package depth.finvibe.profit.worker.infrastructure.kafka;

import depth.finvibe.profit.worker.application.port.in.ProfitCalculationUseCase;
import depth.finvibe.profit.worker.dto.ProfitCalculationDto;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class StockPriceEventConsumerTest {

    @Test
    void consumesMarketStockPriceUpdatedEvent() {
        ProfitCalculationUseCase profitCalculationUseCase = mock(ProfitCalculationUseCase.class);
        StockPriceEventConsumer consumer = new StockPriceEventConsumer(profitCalculationUseCase);

        consumer.consumeStockPriceUpdatedEvent("""
                {
                  "stockId": 123,
                  "price": 72000,
                  "updatedAt": "2026-05-13T13:00:00"
                }
                """);

        ArgumentCaptor<ProfitCalculationDto.ProfitCalculationRequest> captor =
                ArgumentCaptor.forClass(ProfitCalculationDto.ProfitCalculationRequest.class);
        verify(profitCalculationUseCase).updateProfitByStockPriceChange(captor.capture());

        ProfitCalculationDto.ProfitCalculationRequest request = captor.getValue();
        assertThat(request.getStockId()).isEqualTo(123L);
        assertThat(request.getNewPrice()).isEqualTo(72000L);
        assertThat(request.getTimestamp()).isEqualTo(
                LocalDateTime.parse("2026-05-13T13:00:00").atZone(ZoneId.systemDefault()).toInstant()
        );
    }

    @Test
    void rejectsFractionalStockPrice() {
        ProfitCalculationUseCase profitCalculationUseCase = mock(ProfitCalculationUseCase.class);
        StockPriceEventConsumer consumer = new StockPriceEventConsumer(profitCalculationUseCase);

        assertThatThrownBy(() -> consumer.consumeStockPriceUpdatedEvent("""
                {
                  "stockId": 123,
                  "price": 72000.5,
                  "updatedAt": "2026-05-13T13:00:00"
                }
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("price must be an integer");
        verifyNoInteractions(profitCalculationUseCase);
    }
}
