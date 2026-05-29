package depth.finvibe.profit.worker.infrastructure.kafka;

import depth.finvibe.profit.worker.application.ProfitWorkerMetrics;
import depth.finvibe.profit.worker.application.port.in.ProfitCalculationUseCase;
import depth.finvibe.profit.worker.dto.ProfitCalculationDto;
import depth.finvibe.profit.worker.support.TestMetricsFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        StockPriceEventConsumer consumer = new StockPriceEventConsumer(profitCalculationUseCase, fixture.metrics());

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
        assertThat(registry.find(ProfitWorkerMetrics.EVENTS_CONSUMED)
                .tags(ProfitWorkerMetrics.TAG_EVENT_TYPE, ProfitWorkerMetrics.EVENT_TYPE_STOCK_PRICE_UPDATED,
                        ProfitWorkerMetrics.TAG_RESULT, ProfitWorkerMetrics.RESULT_SUCCESS)
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find(ProfitWorkerMetrics.LISTENER_DURATION)
                .tags(ProfitWorkerMetrics.TAG_EVENT_TYPE, ProfitWorkerMetrics.EVENT_TYPE_STOCK_PRICE_UPDATED,
                        ProfitWorkerMetrics.TAG_RESULT, ProfitWorkerMetrics.RESULT_SUCCESS)
                .timer().count()).isEqualTo(1);
        assertThat(registry.find(ProfitWorkerMetrics.EVENT_AGE)
                .tags(ProfitWorkerMetrics.TAG_EVENT_TYPE, ProfitWorkerMetrics.EVENT_TYPE_STOCK_PRICE_UPDATED)
                .timer().count()).isEqualTo(1);
    }

    @Test
    void rejectsFractionalStockPrice() {
        ProfitCalculationUseCase profitCalculationUseCase = mock(ProfitCalculationUseCase.class);
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        StockPriceEventConsumer consumer = new StockPriceEventConsumer(profitCalculationUseCase, fixture.metrics());

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
        assertThat(registry.find(ProfitWorkerMetrics.EVENTS_CONSUMED)
                .tags(ProfitWorkerMetrics.TAG_EVENT_TYPE, ProfitWorkerMetrics.EVENT_TYPE_STOCK_PRICE_UPDATED,
                        ProfitWorkerMetrics.TAG_RESULT, ProfitWorkerMetrics.RESULT_FAILURE)
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void recordsFailureMetricsForInvalidJson() {
        ProfitCalculationUseCase profitCalculationUseCase = mock(ProfitCalculationUseCase.class);
        TestMetricsFactory.MetricsFixture fixture = TestMetricsFactory.create();
        SimpleMeterRegistry registry = fixture.registry();
        StockPriceEventConsumer consumer = new StockPriceEventConsumer(profitCalculationUseCase, fixture.metrics());

        assertThatThrownBy(() -> consumer.consumeStockPriceUpdatedEvent("{not-json}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid Kafka event payload");

        assertThat(registry.find(ProfitWorkerMetrics.EVENTS_CONSUMED)
                .tags(ProfitWorkerMetrics.TAG_EVENT_TYPE, ProfitWorkerMetrics.EVENT_TYPE_STOCK_PRICE_UPDATED,
                        ProfitWorkerMetrics.TAG_RESULT, ProfitWorkerMetrics.RESULT_FAILURE)
                .counter().count()).isEqualTo(1.0);
    }
}
