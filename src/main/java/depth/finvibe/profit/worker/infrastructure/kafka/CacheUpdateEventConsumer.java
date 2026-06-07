package depth.finvibe.profit.worker.infrastructure.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import depth.finvibe.profit.worker.application.ProfitWorkerMetrics;
import depth.finvibe.profit.worker.application.port.in.CacheUpdateUseCase;
import depth.finvibe.profit.worker.dto.CacheUpdateDto;
import depth.finvibe.profit.worker.infrastructure.kafka.dto.PortfolioTradeEvent;
import depth.finvibe.profit.worker.infrastructure.kafka.dto.PortfolioUserEvent;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CacheUpdateEventConsumer {

    private final CacheUpdateUseCase cacheUpdateUseCase;
    private final ProfitWorkerMetrics metrics;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @KafkaListener(
            topics = "${app.kafka.topics.portfolio-trade:trade.trade-executed.v1}",
            groupId = "${app.kafka.consumer.groups.trade:profit-worker-trade}",
            concurrency = "${app.kafka.consumer.concurrency.trade:1}"
    )
    public void consumePortfolioTradeEvents(List<String> payloads) {
        List<CacheUpdateDto.PortfolioCacheUpdateRequest> requests = new ArrayList<>();
        List<Timer.Sample> samples = new ArrayList<>();

        for (String payload : payloads) {
            Timer.Sample sample = metrics.startSample();
            try {
                PortfolioTradeEvent event = read(payload, PortfolioTradeEvent.class);
                requests.add(CacheUpdateDto.PortfolioCacheUpdateRequest.builder()
                        .portfolioId(event.getPortfolioId())
                        .stockId(event.getStockId())
                        .type(toTradeType(event.getType()))
                        .price(event.getPrice())
                        .quantity(toQuantity(event))
                        .build());
                samples.add(sample);
            } catch (RuntimeException ex) {
                recordPortfolioTradeEvent(sample, ProfitWorkerMetrics.RESULT_FAILURE);
                for (Timer.Sample parsedSample : samples) {
                    recordPortfolioTradeEvent(parsedSample, ProfitWorkerMetrics.RESULT_FAILURE);
                }
                throw ex;
            }
        }

        if (requests.isEmpty()) {
            return;
        }

        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            cacheUpdateUseCase.updatePortfolioCaches(requests);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            for (Timer.Sample sample : samples) {
                recordPortfolioTradeEvent(sample, result);
            }
        }
    }

    @KafkaListener(
            topics = "${app.kafka.topics.portfolio-user:asset.portfolio-group-changed.v1}",
            groupId = "${app.kafka.consumer.groups.portfolio:profit-worker-portfolio}",
            concurrency = "${app.kafka.consumer.concurrency.portfolio:1}"
    )
    public void consumePortfolioUserEvents(List<String> payloads) {
        List<CacheUpdateDto.UserCacheUpdateRequest> requests = new ArrayList<>();
        List<Timer.Sample> samples = new ArrayList<>();

        for (String payload : payloads) {
            Timer.Sample sample = metrics.startSample();
            try {
                PortfolioUserEvent event = read(payload, PortfolioUserEvent.class);
                Instant occurredAt = event.getOccurredAt();
                if (occurredAt != null) {
                    metrics.recordEventAge(ProfitWorkerMetrics.EVENT_TYPE_PORTFOLIO_USER, Duration.between(occurredAt, Instant.now()));
                }

                if (event.getEventType() == PortfolioUserEvent.EventType.UPDATED) {
                    metrics.recordSkipped(ProfitWorkerMetrics.EVENT_TYPE_PORTFOLIO_USER, ProfitWorkerMetrics.REASON_UPDATED_EVENT_IGNORED);
                    recordPortfolioUserEvent(sample, ProfitWorkerMetrics.RESULT_SKIPPED);
                    continue;
                }

                requests.add(CacheUpdateDto.UserCacheUpdateRequest.builder()
                        .userId(event.getUserId())
                        .portfolioId(event.getPortfolioId())
                        .type(toChangeType(event.getEventType()))
                        .build());
                samples.add(sample);
            } catch (RuntimeException ex) {
                recordPortfolioUserEvent(sample, ProfitWorkerMetrics.RESULT_FAILURE);
                for (Timer.Sample parsedSample : samples) {
                    recordPortfolioUserEvent(parsedSample, ProfitWorkerMetrics.RESULT_FAILURE);
                }
                throw ex;
            }
        }

        if (requests.isEmpty()) {
            return;
        }

        String result = ProfitWorkerMetrics.RESULT_FAILURE;
        try {
            cacheUpdateUseCase.updateUserCaches(requests);
            result = ProfitWorkerMetrics.RESULT_SUCCESS;
        } finally {
            for (Timer.Sample sample : samples) {
                recordPortfolioUserEvent(sample, result);
            }
        }
    }

    private void recordPortfolioTradeEvent(Timer.Sample sample, String result) {
        metrics.recordConsumed(ProfitWorkerMetrics.EVENT_TYPE_PORTFOLIO_TRADE, result);
        metrics.recordListenerDuration(ProfitWorkerMetrics.EVENT_TYPE_PORTFOLIO_TRADE, result, sample);
    }

    private void recordPortfolioUserEvent(Timer.Sample sample, String result) {
        metrics.recordConsumed(ProfitWorkerMetrics.EVENT_TYPE_PORTFOLIO_USER, result);
        metrics.recordListenerDuration(ProfitWorkerMetrics.EVENT_TYPE_PORTFOLIO_USER, result, sample);
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

    private BigDecimal toQuantity(PortfolioTradeEvent event) {
        BigDecimal amount = event.getAmount();
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Trade event amount must be positive: " + amount);
        }
        return amount.stripTrailingZeros();
    }

    private CacheUpdateDto.UserCacheUpdateRequest.ChangeType toChangeType(PortfolioUserEvent.EventType eventType) {
        return switch (eventType) {
            case CREATED -> CacheUpdateDto.UserCacheUpdateRequest.ChangeType.CREATED;
            case DELETED -> CacheUpdateDto.UserCacheUpdateRequest.ChangeType.DELETED;
            case UPDATED -> throw new IllegalArgumentException("Updated portfolio event does not change user cache");
        };
    }
}
