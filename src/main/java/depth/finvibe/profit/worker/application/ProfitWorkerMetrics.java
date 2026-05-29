package depth.finvibe.profit.worker.application;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ProfitWorkerMetrics {

    public static final String EVENTS_CONSUMED = "profit.worker.events.consumed";
    public static final String EVENTS_SKIPPED = "profit.worker.events.skipped";
    public static final String LISTENER_DURATION = "profit.worker.listener.duration";
    public static final String SERVICE_DURATION = "profit.worker.service.duration";
    public static final String REDIS_OPERATION_DURATION = "profit.worker.redis.operation.duration";
    public static final String AFFECTED_PORTFOLIOS = "profit.worker.affected.portfolios";
    public static final String AFFECTED_USERS = "profit.worker.affected.users";
    public static final String EVENT_AGE = "profit.worker.event.age";

    public static final String TAG_EVENT_TYPE = "event_type";
    public static final String TAG_RESULT = "result";
    public static final String TAG_REASON = "reason";
    public static final String TAG_OPERATION = "operation";

    public static final String RESULT_SUCCESS = "success";
    public static final String RESULT_FAILURE = "failure";
    public static final String RESULT_SKIPPED = "skipped";

    public static final String EVENT_TYPE_STOCK_PRICE_UPDATED = "stock_price_updated";
    public static final String EVENT_TYPE_PORTFOLIO_TRADE = "portfolio_trade";
    public static final String EVENT_TYPE_PORTFOLIO_USER = "portfolio_user";

    public static final String REASON_UPDATED_EVENT_IGNORED = "updated_event_ignored";

    public static final String OPERATION_STOCK_PRICE_RECALCULATION = "stock_price_recalculation";
    public static final String OPERATION_PORTFOLIO_CACHE_UPDATE = "portfolio_cache_update";
    public static final String OPERATION_USER_CACHE_UPDATE = "user_cache_update";
    public static final String OPERATION_PORTFOLIO_CURRENT_VALUE = "portfolio_current_value";
    public static final String OPERATION_USER_CURRENT_VALUE = "user_current_value";
    public static final String OPERATION_PORTFOLIO_VALUATION_SAVE = "portfolio_valuation_save";
    public static final String OPERATION_USER_VALUATION_SAVE = "user_valuation_save";

    private final MeterRegistry meterRegistry;

    public ProfitWorkerMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    public Timer.Sample startSample() {
        return Timer.start(meterRegistry);
    }

    public void recordConsumed(String eventType, String result) {
        safeRecord(() -> meterRegistry.counter(EVENTS_CONSUMED,
                TAG_EVENT_TYPE, eventType,
                TAG_RESULT, result).increment());
    }

    public void recordSkipped(String eventType, String reason) {
        safeRecord(() -> meterRegistry.counter(EVENTS_SKIPPED,
                TAG_EVENT_TYPE, eventType,
                TAG_REASON, reason).increment());
    }

    public void recordListenerDuration(String eventType, String result, Timer.Sample sample) {
        safeRecord(() -> sample.stop(Timer.builder(LISTENER_DURATION)
                .tag(TAG_EVENT_TYPE, eventType)
                .tag(TAG_RESULT, result)
                .register(meterRegistry)));
    }

    public void recordServiceDuration(String operation, String result, Timer.Sample sample) {
        safeRecord(() -> sample.stop(Timer.builder(SERVICE_DURATION)
                .tag(TAG_OPERATION, operation)
                .tag(TAG_RESULT, result)
                .register(meterRegistry)));
    }

    public void recordRedisDuration(String operation, String result, Timer.Sample sample) {
        safeRecord(() -> sample.stop(Timer.builder(REDIS_OPERATION_DURATION)
                .tag(TAG_OPERATION, operation)
                .tag(TAG_RESULT, result)
                .register(meterRegistry)));
    }

    public void recordAffectedPortfolios(String operation, long count) {
        safeRecord(() -> DistributionSummary.builder(AFFECTED_PORTFOLIOS)
                .tag(TAG_OPERATION, operation)
                .register(meterRegistry)
                .record(count));
    }

    public void recordAffectedUsers(String operation, long count) {
        safeRecord(() -> DistributionSummary.builder(AFFECTED_USERS)
                .tag(TAG_OPERATION, operation)
                .register(meterRegistry)
                .record(count));
    }

    public void recordEventAge(String eventType, Duration duration) {
        if (duration == null || duration.isNegative()) {
            return;
        }

        safeRecord(() -> Timer.builder(EVENT_AGE)
                .tag(TAG_EVENT_TYPE, eventType)
                .register(meterRegistry)
                .record(duration));
    }

    private void safeRecord(Runnable recording) {
        try {
            recording.run();
        } catch (RuntimeException ignored) {
            // Metrics must never affect worker flow.
        }
    }
}
