package depth.finvibe.profit.worker.application;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProfitWorkerMetricsTest {

    @Test
    void recordsCounterAndTimerMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProfitWorkerMetrics metrics = new ProfitWorkerMetrics(registry);

        metrics.recordConsumed(ProfitWorkerMetrics.EVENT_TYPE_STOCK_PRICE_UPDATED, ProfitWorkerMetrics.RESULT_SUCCESS);
        Timer.Sample sample = metrics.startSample();
        metrics.recordListenerDuration(ProfitWorkerMetrics.EVENT_TYPE_STOCK_PRICE_UPDATED, ProfitWorkerMetrics.RESULT_SUCCESS, sample);
        metrics.recordAffectedPortfolios(ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION, 2);

        assertThat(registry.find(ProfitWorkerMetrics.EVENTS_CONSUMED)
                .tags(ProfitWorkerMetrics.TAG_EVENT_TYPE, ProfitWorkerMetrics.EVENT_TYPE_STOCK_PRICE_UPDATED,
                        ProfitWorkerMetrics.TAG_RESULT, ProfitWorkerMetrics.RESULT_SUCCESS)
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find(ProfitWorkerMetrics.LISTENER_DURATION)
                .tags(ProfitWorkerMetrics.TAG_EVENT_TYPE, ProfitWorkerMetrics.EVENT_TYPE_STOCK_PRICE_UPDATED,
                        ProfitWorkerMetrics.TAG_RESULT, ProfitWorkerMetrics.RESULT_SUCCESS)
                .timer().count()).isEqualTo(1);
        assertThat(registry.find(ProfitWorkerMetrics.AFFECTED_PORTFOLIOS)
                .tags(ProfitWorkerMetrics.TAG_OPERATION, ProfitWorkerMetrics.OPERATION_STOCK_PRICE_RECALCULATION)
                .summary().totalAmount()).isEqualTo(2.0);
    }

    @Test
    void skipsNegativeEventAge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ProfitWorkerMetrics metrics = new ProfitWorkerMetrics(registry);

        metrics.recordEventAge(ProfitWorkerMetrics.EVENT_TYPE_PORTFOLIO_USER, Duration.ofSeconds(-1));

        assertThat(registry.find(ProfitWorkerMetrics.EVENT_AGE)
                .tags(ProfitWorkerMetrics.TAG_EVENT_TYPE, ProfitWorkerMetrics.EVENT_TYPE_PORTFOLIO_USER)
                .timer()).isNull();
    }
}
